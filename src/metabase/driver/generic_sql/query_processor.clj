(ns metabase.driver.generic-sql.query-processor
  "The Query Processor is responsible for translating the Metabase Query Language into HoneySQL SQL forms."
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [honeysql
             [core :as hsql]
             [helpers :as h]]
            [metabase
             [driver :as driver]
             [util :as u]]
            [metabase.driver.generic-sql :as sql]
            [metabase.mbql.util :as mbql.u]
            [metabase.models.database :refer [Database]]
            [metabase.query-processor
             [annotate :as annotate]
             [interface :as i]
             [store :as qp.store]
             [util :as qputil]]
            [metabase.util
             [date :as du]
             [honeysql-extensions :as hx]
             [i18n :refer [tru]]]
            [schema.core :as s]
            [metabase.mbql.schema :as mbql.s])
  (:import [java.sql PreparedStatement ResultSet ResultSetMetaData SQLException]
           [java.util Calendar Date TimeZone]
           metabase.models.field.FieldInstance))

;; TODO - yet another `*query*` dynamic var. We should really consolidate them all so we only need a single one.
(def ^:dynamic *query*
  "The outer query currently being processed."
  nil)

(def ^:private ^:dynamic *nested-query-level*
  "How many levels deep are we into nested queries? (0 = top level.) We keep track of this so we know what level to
  find referenced aggregations (otherwise something like [:aggregation 0] could be ambiguous in a nested query).
  Each nested query increments this counter by 1."
  0)

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                Other Formatting                                                |
;;; +----------------------------------------------------------------------------------------------------------------+


(s/defn ^:private qualified-alias
  "Convert the given `FIELD` to a stringified alias"
  [driver, field :- FieldInstance]
  (some->> field
           (sql/field->alias driver)
           hx/qualify-and-escape-dots))

(defn as
  "Generate a FORM `AS` FIELD alias using the name information of FIELD."
  [driver form field-clause]
  (let [id-or-name (mbql.u/field-clause->id-or-literal field-clause)
        field      (when (integer? id-or-name) (qp.store/field id-or-name))]
    (if-let [alias (when field
                     (qualified-alias driver field))]
      [form alias]
      form)))

;; TODO - Consider moving this into query processor interface and making it a method on `ExpressionRef` instead ?
(defn- expression-with-name
  "Return the `Expression` referenced by a given (keyword or string) EXPRESSION-NAME."
  [expression-name]
  (or (get-in *query* [:query :expressions (keyword expression-name)]) (:expressions (:query *query*))
      (throw (Exception. (format "No expression named '%s'." (name expression-name))))))

(defn- aggregation-at-index
  "Fetch the aggregation at index. This is intended to power aggregate field references (e.g. [:aggregation 0]).
   This also handles nested queries, which could be potentially ambiguous if multiple levels had aggregations."
  ([index]
   (aggregation-at-index index (:query *query*) *nested-query-level*))
  ;; keep recursing deeper into the query until we get to the same level the aggregation reference was defined at
  ([index query aggregation-level]
   (if (zero? aggregation-level)
     (nth (:aggregation query) index)
     (recur index (:source-query query) (dec aggregation-level)))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                              ->honeysql multimethod def & low-level method impls                               |
;;; +----------------------------------------------------------------------------------------------------------------+

;; this is the primary way to override behavior for a specific clause or object class.

(defmulti ^{:doc (str "Return an appropriate HoneySQL form for an object. Dispatches off both driver and either clause "
                      "name or object class making this easy to override in any places needed for a given driver.")
            :arglists     '([driver x])
            :style/indent 1}
  ->honeysql
  (fn [driver x]
    [(class driver) (mbql.u/dispatch-by-clause-name-or-class x)]))

(defmethod ->honeysql [Object nil]    [_ _]    nil)
(defmethod ->honeysql [Object Object] [_ this] this)

(defmethod ->honeysql [Object :value] [driver [_ value]] (->honeysql driver value))

(defn- expression->honeysql [driver operator & args]
  (apply (partial hsql/call operator)
         (map (partial ->honeysql driver) args)))

(defmethod ->honeysql [Object :expression]
  [driver [_ expression-name]]
  ;; Unfortunately you can't just refer to the expression by name in other clauses like filter, but have to use the
  ;; original formula.
  (->honeysql driver (expression-with-name expression-name)))

(defmethod ->honeysql [Object :field-id]
  [driver [_ field-id]]
  (let [field (qp.store/field field-id)
        table (qp.store/table (:table_id field))
        field (keyword (hx/qualify-and-escape-dots (:schema table) (:name table) (:name field)))]
    (cond
      (isa? (:special_type field) :type/UNIXTimestampSeconds)      (sql/unix-timestamp->timestamp driver field :seconds)
      (isa? (:special_type field) :type/UNIXTimestampMilliseconds) (sql/unix-timestamp->timestamp driver field :milliseconds)
      :else                                                        field)))

(defmethod ->honeysql [Object :fk->]
  [driver [_ _ dest-field-clause]]
  (->honeysql driver dest-field-clause))

(defmethod ->honeysql [Object :field-literal]
  [driver [_ field-name]]
  (->honeysql driver (keyword (hx/escape-dots (name field-name)))))

(defmethod ->honeysql [Object :datetime-field]
  [driver [_ field unit]]
  (sql/date driver unit (->honeysql driver field)))

(defmethod ->honeysql [Object :binning-strategy]
  [driver [_ field _ {:keys [bin-width min-value max-value field]}]]
  (let [honeysql-field-form (->honeysql driver field)]
    ;;
    ;; Equation is | (value - min) |
    ;;             | ------------- | * bin-width + min-value
    ;;             |_  bin-width  _|
    ;;
    (-> honeysql-field-form
        (hx/- min-value)
        (hx// bin-width)
        hx/floor
        (hx/* bin-width)
        (hx/+ min-value))))

(defn- aggregation->honeysql
  "Generate the HoneySQL form for an aggregation."
  [driver aggregation-type field]
  {:pre [(keyword? aggregation-type)]}
  (let [f (case aggregation-type
            :avg      :avg
            :count    :count
            :distinct :distinct-count
            :stddev   (sql/stddev-fn driver)
            :sum      :sum
            :min      :min
            :max      :max)]
    (if field
      (hsql/call f (->honeysql driver field))
      (keyword (str \% (name f) ".*")))))

;; TODO - can't we just roll this into the ->honeysql method for `expression`?
(defn- expression-aggregation->honeysql
  "Generate the HoneySQL form for an expression aggregation."
  [driver [operator & args]]
  (->honeysql driver
    (apply vector operator (for [arg args]
                             (cond
                               (number? arg)
                               arg

                               (mbql.u/is-clause? #{:- :+ :* :/} arg)
                               (expression-aggregation->honeysql driver arg)

                               :else
                               (aggregation->honeysql driver arg))))))

;; e.g. the ["aggregation" 0] fields we allow in order-by
(defmethod ->honeysql [Object :aggregation]
  [driver [_ index]]
  (let [aggregation (aggregation-at-index index)]
    (cond
      ;; For some arcane reason we name the results of a distinct aggregation "count",
      ;; everything else is named the same as the aggregation
      (mbql.u/is-clause? :distinct aggregation)
      :count

      (mbql.u/is-clause? :expression aggregation)
      (expression-aggregation->honeysql driver aggregation)

      ;; for everything else just use the name of the aggregation as an identifer, e.g. `:sum`
      ;; TODO - this obviously doesn't work right for multiple aggregations of the same type
      :else
      (first aggregation))))

(defmethod ->honeysql [Object :absolute-datetime]
  [driver [_ timestamp unit]]
  (sql/date driver unit (->honeysql driver timestamp)))

(defmethod ->honeysql [Object :relative-datetime]
  [driver [_ amount unit]]
  (sql/date driver unit (if (zero? amount)
                          (sql/current-datetime-fn driver)
                          (driver/date-interval driver unit amount))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                Clause Handlers                                                 |
;;; +----------------------------------------------------------------------------------------------------------------+

;;; -------------------------------------------------- aggregation ---------------------------------------------------

(defn- apply-expression-aggregation [driver honeysql-form expression]
  (h/merge-select
   honeysql-form
   [(expression-aggregation->honeysql driver expression)
    (hx/escape-dots (driver/format-custom-field-name driver (annotate/aggregation-name expression)))]))

(defn- apply-single-aggregation [driver honeysql-form [aggregation-type field :as aggregation]]
  (h/merge-select honeysql-form [(aggregation->honeysql driver aggregation-type field)
                                 (hx/escape-dots (annotate/aggregation-name aggregation))]))

(defn apply-aggregation
  "Apply `aggregation` clauses to HONEYSQL-FORM. Default implementation of `apply-aggregation` for SQL drivers."
  [driver honeysql-form {aggregations :aggregation}]
  (loop [form honeysql-form, [ag & more] aggregations]
    (let [form (if (mbql.u/is-clause? #{:+ :- :/ :*} ag)
                 (apply-expression-aggregation driver form ag)
                 (apply-single-aggregation driver form ag))]
      (if-not (seq more)
        form
        (recur form more)))))

;;; ----------------------------------------------- breakout & fields ------------------------------------------------

(defn apply-breakout
  "Apply a `breakout` clause to HONEYSQL-FORM. Default implementation of `apply-breakout` for SQL drivers."
  [driver honeysql-form {breakout-fields :breakout, fields-fields :fields :as query}]
  (as-> honeysql-form new-hsql
    (apply h/merge-select new-hsql (for [field-clause breakout-fields
                                         :when        (not (contains? (set fields-fields) field-clause))]
                                     (as driver (->honeysql driver field-clause) field-clause)))
    (apply h/group new-hsql (map (partial ->honeysql driver) breakout-fields))))

(defn apply-fields
  "Apply a `fields` clause to HONEYSQL-FORM. Default implementation of `apply-fields` for SQL drivers."
  [driver honeysql-form {fields :fields}]
  (apply h/merge-select honeysql-form (for [field fields]
                                        (as driver (->honeysql driver field) field))))


;;; ----------------------------------------------------- filter -----------------------------------------------------

(defn- like-clause
  "Generate a SQL `LIKE` clause. `value` is assumed to be a `Value` object (a record type with a key `:value` as well as
  some sort of type info) or similar as opposed to a raw value literal."
  [driver field value options]
  ;; TODO - don't we need to escape underscores and percent signs in the pattern, since they have special meanings in
  ;; LIKE clauses? That's what we're doing with Druid...
  ;;
  ;; TODO - Postgres supports `ILIKE`. Does that make a big enough difference performance-wise that we should do a
  ;; custom implementation?
  (if (get options :case-sensitive true)
    [:like field                    (->honeysql driver value)]
    [:like (hsql/call :lower field) (->honeysql driver (update value 1 str/lower-case))]))

(defmethod ->honeysql [Object :between] [driver [_ field min-val max-val]]
  [:between (->honeysql driver field) (->honeysql driver min-val) (->honeysql driver max-val)])

(defmethod ->honeysql [Object :starts-with] [driver [_ field value options]]
  (like-clause driver (->honeysql driver field) (update value 1 #(str % \%)) options))

(defmethod ->honeysql [Object :contains] [driver [_ field value options]]
  (like-clause driver (->honeysql driver field) (update value 1 #(str \% % \%)) options))

(defmethod ->honeysql [Object :ends-with] [driver [_ field value options]]
  (like-clause driver (->honeysql driver field) (update value 1 #(str \% %)) options))

(defmethod ->honeysql [Object :>] [driver [_ field value]]
  [:> (->honeysql driver field) (->honeysql driver value)])

(defmethod ->honeysql [Object :<] [driver [_ field value]]
  [:< (->honeysql driver field) (->honeysql driver value)])

(defmethod ->honeysql [Object :>=] [driver [_ field value]]
  [:>= (->honeysql driver field) (->honeysql driver value)])

(defmethod ->honeysql [Object :<=] [driver [_ field value]]
  [:<= (->honeysql driver field) (->honeysql driver value)])

(defmethod ->honeysql [Object :=] [driver [_ field value]]
  [:= (->honeysql driver field) (->honeysql driver value)])

(defmethod ->honeysql [Object :!=] [driver [_ field value]]
  [:not= (->honeysql driver field) (->honeysql driver value)])

(defmethod ->honeysql [Object :and] [driver [_ & subclauses]]
  (apply vector :and (map (partial ->honeysql driver) subclauses)))

(defmethod ->honeysql [Object :or] [driver [_ & subclauses]]
  (apply vector :or (map (partial ->honeysql driver) subclauses)))

(defmethod ->honeysql [Object :not] [driver [_ subclause]]
  [:not (->honeysql driver subclause)])

(defn apply-filter
  "Apply a `filter` clause to HONEYSQL-FORM. Default implementation of `apply-filter` for SQL drivers."
  [driver honeysql-form {clause :filter}]
  (h/where honeysql-form (->honeysql driver clause)))


;;; -------------------------------------------------- join tables ---------------------------------------------------

(declare build-honeysql-form)

(defn- make-honeysql-join-clauses
  "Returns a seq of honeysql join clauses, joining to `table-or-query-expr`. `jt-or-jq` can be either a `JoinTable` or
  a `JoinQuery`"
  [table-or-query-expr {:keys [join-alias fk-field-id pk-field-id]}]
  (let [source-table-id                                  (mbql.u/query->source-table-id *query*)
        {source-table-name :name, source-schema :schema} (qp.store/table source-table-id)
        source-field                                     (qp.store/field fk-field-id)
        pk-field                                         (qp.store/field pk-field-id)]
    [[table-or-query-expr (keyword join-alias)]
     [:=
      (hx/qualify-and-escape-dots source-schema source-table-name (:name source-field))
      (hx/qualify-and-escape-dots join-alias (:name pk-field))]]))

(s/defn ^:private join-info->honeysql
  [driver , {:keys [query table-id], :as info} :- mbql.s/JoinInfo]
  (if query
    (make-honeysql-join-clauses (build-honeysql-form driver query) info)
    (let [table (qp.store/table table-id)]
      (make-honeysql-join-clauses (hx/qualify-and-escape-dots (:schema table) (:name table)) info))))

(defn apply-join-tables
  "Apply expanded query `join-tables` clause to `honeysql-form`. Default implementation of `apply-join-tables` for SQL
  drivers."
  [driver honeysql-form {:keys [join-tables]}]
  (reduce (partial apply h/merge-left-join) honeysql-form (map (partial join-info->honeysql driver) join-tables)))


;;; ---------------------------------------------------- order-by ----------------------------------------------------

(defn apply-order-by
  "Apply `order-by` clause to HONEYSQL-FORM. Default implementation of `apply-order-by` for SQL drivers."
  [driver honeysql-form {subclauses :order-by breakout-fields :breakout}]
  (let [[{:keys [special-type] :as first-breakout-field}] breakout-fields]
    (loop [honeysql-form honeysql-form, [[direction field] & more] subclauses]
      (let [honeysql-form (h/merge-order-by honeysql-form [(->honeysql driver field) direction])]
        (if (seq more)
          (recur honeysql-form more)
          honeysql-form)))))

;;; -------------------------------------------------- limit & page --------------------------------------------------

(defn apply-limit
  "Apply `limit` clause to HONEYSQL-FORM. Default implementation of `apply-limit` for SQL drivers."
  [_ honeysql-form {value :limit}]
  (h/limit honeysql-form value))

(defn apply-page
  "Apply `page` clause to HONEYSQL-FORM. Default implementation of `apply-page` for SQL drivers."
  [_ honeysql-form {{:keys [items page]} :page}]
  (-> honeysql-form
      (h/limit items)
      (h/offset (* items (dec page)))))


;;; ------------------------------------------ source-table & source-query -------------------------------------------

(defn apply-source-table
  "Apply `source-table` clause to `honeysql-form`. Default implementation of `apply-source-table` for SQL drivers.
  Override as needed."
  [_ honeysql-form {source-table-id :source-table}]
  (let [{table-name :name, schema :schema} (qp.store/table source-table-id)]
    (h/from honeysql-form (hx/qualify-and-escape-dots schema table-name))))

(declare apply-clauses)

(defn- apply-source-query [driver honeysql-form {{:keys [native], :as source-query} :source-query}]
  ;; TODO - what alias should we give the source query?
  (assoc honeysql-form
    :from [[(if native
              (hsql/raw (str "(" (str/replace native #";+\s*$" "") ")")) ; strip off any trailing slashes
              (binding [*nested-query-level* (inc *nested-query-level*)]
                (apply-clauses driver {} source-query)))
            :source]]))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                           Building the HoneySQL Form                                           |
;;; +----------------------------------------------------------------------------------------------------------------+

(def ^:private clause-handlers
  ;; 1) Use the vars rather than the functions themselves because them implementation
  ;;    will get swapped around and  we'll be left with old version of the function that nobody implements
  ;; 2) This is a vector rather than a map because the order the clauses get handled is important for some drivers.
  ;;    For example, Oracle needs to wrap the entire query in order to apply its version of limit (`WHERE ROWNUM`).
  [:source-table #'sql/apply-source-table
   :source-query apply-source-query
   :breakout     #'sql/apply-breakout
   :aggregation  #'sql/apply-aggregation
   :fields       #'sql/apply-fields
   :filter       #'sql/apply-filter
   :join-tables  #'sql/apply-join-tables
   :order-by     #'sql/apply-order-by
   :page         #'sql/apply-page
   :limit        #'sql/apply-limit])

(defn- apply-clauses
  "Loop through all the `clause->handler` entries; if the query contains a given clause, apply the handler fn."
  [driver honeysql-form query]
  (loop [honeysql-form honeysql-form, [clause f & more] (seq clause-handlers)]
    (let [honeysql-form (if (clause query)
                          (f driver honeysql-form query)
                          honeysql-form)]
      (if (seq more)
        (recur honeysql-form more)
        ;; ok, we're done; if no `:select` clause was specified (for whatever reason) put a default (`SELECT *`) one
        ;; in
        (update honeysql-form :select #(if (seq %) % [:*]))))))

(defn build-honeysql-form
  "Build the HoneySQL form we will compile to SQL and execute."
  [driverr {inner-query :query}]
  {:pre [(map? inner-query)]}
  (u/prog1 (apply-clauses driverr {} inner-query)
    (when-not i/*disable-qp-logging*
      (log/debug (tru "HoneySQL Form:") (u/emoji "🍯") "\n" (u/pprint-to-str 'cyan <>)))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                 MBQL -> Native                                                 |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn mbql->native
  "Transpile MBQL query into a native SQL statement."
  [driver {inner-query :query, database :database, :as outer-query}]
  (binding [*query* outer-query]
    (when-not (:quiet environ.core/env) (println "QUERY:" (u/pprint-to-str 'magenta outer-query))) ; NOCOMMIT
    (let [honeysql-form (build-honeysql-form driver outer-query)
          _              (when-not (:quiet environ.core/env) (println "HoneySQL:" (u/pprint-to-str 'yellow honeysql-form))) ; NOCOMMIT
          [sql & args]  (sql/honeysql-form->sql+args driver honeysql-form)]
      {:query  sql
       :params args})))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                Parsing Results                                                 |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- parse-date-as-string
  "Most databases will never invoke this code. It's possible with SQLite to get here if the timestamp was stored
  without milliseconds. Currently the SQLite JDBC driver will throw an exception even though the SQLite datetime
  functions will return datetimes that don't include milliseconds. This attempts to parse that datetime in Clojure
  land"
  [^TimeZone tz ^ResultSet rs ^Integer i]
  (let [date-string (.getString rs i)]
    (if-let [parsed-date (du/str->date-time date-string tz)]
      parsed-date
      (throw (Exception. (format "Unable to parse date '%s'" date-string))))))

(defn- get-date [^TimeZone tz]
  (fn [^ResultSet rs _ ^Integer i]
    (try
      (.getDate rs i (Calendar/getInstance tz))
      (catch SQLException e
        (parse-date-as-string tz rs i)))))

(defn- get-timestamp [^TimeZone tz]
  (fn [^ResultSet rs _ ^Integer i]
    (try
      (.getTimestamp rs i (Calendar/getInstance tz))
      (catch SQLException e
        (parse-date-as-string tz rs i)))))

(defn- get-object [^ResultSet rs _ ^Integer i]
  (.getObject rs i))

(defn- make-column-reader
  "Given `COLUMN-TYPE` and `TZ`, return a function for reading that type of column from a ResultSet"
  [column-type tz]
  (cond
    (and tz (= column-type java.sql.Types/DATE))
    (get-date tz)

    (and tz (= column-type java.sql.Types/TIMESTAMP))
    (get-timestamp tz)

    :else
    get-object))

(defn- read-columns-with-date-handling
  "Returns a function that will read a row from `RS`, suitable for
  being passed into the clojure.java.jdbc/query function"
  [timezone]
  (fn [^ResultSet rs ^ResultSetMetaData rsmeta idxs]
    (let [data-read-functions (map (fn [^Integer i] (make-column-reader (.getColumnType rsmeta i) timezone)) idxs)]
      (mapv (fn [^Integer i data-read-fn]
              (jdbc/result-set-read-column (data-read-fn rs rsmeta i) rsmeta i)) idxs data-read-functions))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                Running Queries                                                 |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- set-parameters-with-timezone
  "Returns a function that will set date/timestamp PreparedStatement
  parameters with the correct timezone"
  [^TimeZone tz]
  (fn [^PreparedStatement stmt params]
    (mapv (fn [^Integer i value]
            (cond

              (and tz (instance? java.sql.Time value))
              (.setTime stmt i value (Calendar/getInstance tz))

              (and tz (instance? java.sql.Timestamp value))
              (.setTimestamp stmt i value (Calendar/getInstance tz))

              (and tz (instance? java.util.Date value))
              (.setDate stmt i value (Calendar/getInstance tz))

              :else
              (jdbc/set-parameter value stmt i)))
          (rest (range)) params)))

(defmacro ^:private with-ensured-connection
  "In many of the clojure.java.jdbc functions, it checks to see if there's already a connection open before opening a
  new one. This macro checks to see if one is open, or will open a new one. Will bind the connection to `conn-sym`."
  [conn-sym db & body]
  `(let [db# ~db]
     (if-let [~conn-sym (jdbc/db-find-connection db#)]
       (do ~@body)
       (with-open [~conn-sym (jdbc/get-connection db#)]
         ~@body))))

(defn- cancellable-run-query
  "Runs `sql` in such a way that it can be interrupted via a `future-cancel`"
  [db sql params opts]
  (with-ensured-connection conn db
    ;; This is normally done for us by java.jdbc as a result of our `jdbc/query` call
    (with-open [^PreparedStatement stmt (jdbc/prepare-statement conn sql opts)]
      ;; Need to run the query in another thread so that this thread can cancel it if need be
      (try
        (let [query-future (future (jdbc/query conn (into [stmt] params) opts))]
          ;; This thread is interruptable because it's awaiting the other thread (the one actually running the
          ;; query). Interrupting this thread means that the client has disconnected (or we're shutting down) and so
          ;; we can give up on the query running in the future
          @query-future)
        (catch InterruptedException e
          (log/warn e (tru "Client closed connection, cancelling query"))
          ;; This is what does the real work of cancelling the query. We aren't checking the result of
          ;; `query-future` but this will cause an exception to be thrown, saying the query has been cancelled.
          (.cancel stmt)
          (throw e))))))

(defn- run-query
  "Run the query itself."
  [{sql :query, params :params, remark :remark} timezone connection]
  (let [sql              (str "-- " remark "\n" (hx/unescape-dots sql))
        statement        (into [sql] params)
        [columns & rows] (cancellable-run-query connection sql params
                                                {:identifiers    identity
                                                 :as-arrays?     true
                                                 :read-columns   (read-columns-with-date-handling timezone)
                                                 :set-parameters (set-parameters-with-timezone timezone)})]
    {:rows    (or rows [])
     :columns (map u/keyword->qualified-name columns)}))


;;; -------------------------- Running queries: exception handling & disabling auto-commit ---------------------------

(defn- exception->nice-error-message ^String [^SQLException e]
  (or (->> (.getMessage e)     ; error message comes back like 'Column "ZID" not found; SQL statement: ... [error-code]' sometimes
           (re-find #"^(.*);") ; the user already knows the SQL, and error code is meaningless
           second)             ; so just return the part of the exception that is relevant
      (.getMessage e)))

(defn do-with-try-catch
  "Tries to run the function `f`, catching and printing exception chains if SQLException is thrown,
  and rethrowing the exception as an Exception with a nicely formatted error message."
  {:style/indent 0}
  [f]
  (try (f)
       (catch SQLException e
         (log/error (jdbc/print-sql-exception-chain e))
         (throw (Exception. (exception->nice-error-message e))))))

(defn- do-with-auto-commit-disabled
  "Disable auto-commit for this transaction, and make the transaction `rollback-only`, which means when the
  transaction finishes `.rollback` will be called instead of `.commit`. Furthermore, execute F in a try-finally block;
  in the `finally`, manually call `.rollback` just to be extra-double-sure JDBC any changes made by the transaction
  aren't committed."
  {:style/indent 1}
  [conn f]
  (jdbc/db-set-rollback-only! conn)
  (.setAutoCommit (jdbc/get-connection conn) false)
  ;; TODO - it would be nice if we could also `.setReadOnly` on the transaction as well, but that breaks setting the
  ;; timezone. Is there some way we can have our cake and eat it too?
  (try (f)
       (finally (.rollback (jdbc/get-connection conn)))))

(defn- do-in-transaction [connection f]
  (jdbc/with-db-transaction [transaction-connection connection]
    (do-with-auto-commit-disabled transaction-connection (partial f transaction-connection))))


;;; ---------------------------------------------- Running w/ Timezone -----------------------------------------------

(defn- set-timezone!
  "Set the timezone for the current connection."
  [driver settings connection]
  (let [timezone      (u/prog1 (:report-timezone settings)
                        (assert (re-matches #"[A-Za-z\/_]+" <>)))
        format-string (sql/set-timezone-sql driver)
        sql           (format format-string (str \' timezone \'))]
    (log/debug (u/format-color 'green (tru "Setting timezone with statement: {0}" sql)))
    (jdbc/db-do-prepared connection [sql])))

(defn- run-query-without-timezone [_ _ connection query]
  (do-in-transaction connection (partial run-query query nil)))

(defn- run-query-with-timezone [driver {:keys [^String report-timezone] :as settings} connection query]
  (try
    (do-in-transaction connection (fn [transaction-connection]
                                    (set-timezone! driver settings transaction-connection)
                                    (run-query query
                                               (some-> report-timezone TimeZone/getTimeZone)
                                               transaction-connection)))
    (catch SQLException e
      (log/error (tru "Failed to set timezone:") "\n" (with-out-str (jdbc/print-sql-exception-chain e)))
      (run-query-without-timezone driver settings connection query))
    (catch Throwable e
      (log/error (tru "Failed to set timezone:") "\n" (.getMessage e))
      (run-query-without-timezone driver settings connection query))))


;;; ------------------------------------------------- execute-query --------------------------------------------------

(defn execute-query
  "Process and run a native (raw SQL) QUERY."
  [driver {settings :settings, database-id :database, query :native, :as outer-query}]
  (let [query (assoc query :remark (qputil/query->remark outer-query))]
    (when-not (:quiet environ.core/env) (println "NATIVE QUERY:" (u/pprint-to-str 'blue query))) ; NOCOMMIT
    (do-with-try-catch
      (fn []
        (let [db-connection (sql/db->jdbc-connection-spec (Database (u/get-id database-id)))]
          ((if (seq (:report-timezone settings))
             run-query-with-timezone
             run-query-without-timezone) driver settings db-connection query))))))
