(ns ns-clone.datomic-clone-test
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.test :refer :all]
    [clojure.spec.test.alpha :as st]
    [ns-clone.core :as clone]
    [datomic-clone.api :as d]                               ; << do this replacement in your api consumer namespaces
    [ns-clone.middleware :as middleware]
    [io.pedestal.interceptor.chain :as chain]
    [io.pedestal.interceptor.helpers :as helpers :refer [before]]
    [clojure.spec.alpha :as s])
  (:import (clojure.lang ExceptionInfo)
           (java.util UUID)))

(st/instrument)

;;;;;;;;; SETUP ;;;;;;;;

(def fake-conn "datomic conn")
(def fake-db "datomic db value")

(s/def ::username string?)
(s/def ::user (s/keys :req [::username]))
; this is the fake app context spec. used below in :basic-tests and :middleware-tests
(s/def ::test-app-context (s/keys :req [::user]))

; not logging d/tempid or d/squuid calls since the log atom is not available and they are not very useful in logs anyway

; need a delegate for d/tempid, shared by all app combinations
(defmethod d/tempid-context :all [partition]
  {::chain/queue [(before :tempid-delegate (fn [context]
                                             (assoc context ::clone/result (str partition "-" (UUID/randomUUID)))))]})

; need a delegate for d/squuid, shared by all app combinations
(defmethod d/squuid-context :all []
  {::chain/queue [(before :tempid-delegate (fn [context]
                                             (assoc context ::clone/result (UUID/randomUUID))))]})

;;;; shared delegate interceptors ;;;;

; these interceptors and last in the queue and call the underlying cloned fns. in these tests they mock the Datomic api calls.

(defn db-delegate
  "return an interceptor that mocks a datomic d/db call"
  [conn]
  (before :db-delegate (fn [context]
                         (let [db-result fake-db            ; << a real delegate would invoke d/db here
                               wrapped-db-value (assoc conn ::clone/UNSAFE! db-result)]
                           (assoc context ::clone/result wrapped-db-value)))))

(defn pull-delegate
  "return an interceptor that mocks a datomic d/pull call"
  [db pattern eid]
  (before :pull-delegate (fn [context]
                           ; a real delegate would invoke d/pull here, using the db arg
                           (let [pull-result {:db/id        eid
                                              :identity/key 42}]
                             (assoc context ::clone/result pull-result)))))

(defn query-delegate
  [_ _]
  (before :query-delegate (fn [context]
                            ; a real delegate would invoke d/q here, using the db arg
                            (let [query-result #{[100 42]}]
                              (assoc context ::clone/result query-result)))))

(defn attribute-delegate
  [_]
  (before :attribute-delegate (fn [context]
                                ; a real delegate would invoke d/attribute here
                                (let [attr-result 42]
                                  (assoc context ::clone/result attr-result)))))

(defn transact-delegate
  [conn _]
  (before :transact-delegate (fn [context]
                               ; a real delegate would invoke d/transact here
                               (assoc context ::clone/result
                                              (atom         ; wrap result in an atom to emulate the future
                                                {:tx-result {}
                                                 :db-before (assoc conn ::clone/UNSAFE! {})
                                                 :db-after  (assoc conn ::clone/UNSAFE! {})})))))

(defn resolve-tempid-delegate
  [_ _ _]
  (before :transact-delegate (fn [context]
                               ; a real delegate would invoke d/resolve-tempid here
                               (assoc context ::clone/result 123))))

;;;;;;;;; TESTS ;;;;;;;;

;;;; :basic-test multi-methods ;;;;

; if you added specs for your clone/api fns, then implement the multi-spec to enable them for each app key
; this is also optional but, if not done, your specs will be ignored
(defmethod clone/with-app-context :basic-tests [_] ::test-app-context)

; methods to return Pedestal contexts (interceptor chains) for each wrapped fn

(defmethod d/db-context :basic-tests [conn]
  {::chain/queue [(db-delegate conn)]})

(defmethod d/pull-context :basic-tests [db pattern eid]
  {::chain/queue [(pull-delegate db pattern eid)]})

(defmethod d/query-context :basic-tests
  [& args]
  {::chain/queue [(query-delegate (first args) (rest args))]})

(defmethod d/attribute-context :basic-tests
  [& args]
  {::chain/queue [(attribute-delegate (second args))]})

(defmethod d/transact-context :basic-tests [conn data]
  {::chain/queue [(transact-delegate conn data)]})

(defmethod d/resolve-tempid-context :basic-tests [db tempids temp-id]
  {::chain/queue [(resolve-tempid-delegate db tempids temp-id)]})

(deftest basic-read-and-write                               ; using :basic-tests config above

  (let [app-context {::username "Dave"}
        ; this is a wrapped arg which provides all data required for the app interceptor chain
        conn {::clone/app     :basic-tests
              ::clone/UNSAFE! fake-conn
              ::user          app-context}
        ; below looks just like the datomic api
        db (d/db conn)]

    (testing "database value"
      (is (= fake-db (::clone/UNSAFE! db)) "conn was transformed into a db ")
      (is (= app-context (get-in db [::user])) "app context is present in db"))

    (testing "pull"
      (is (= {:db/id        234
              :identity/key 42}
             ; below looks just like the datomic api
             (d/pull db '[:db/id :identity/key] 234))
          "pull returned data from delegate interceptor"))

    (testing "query"
      (is (= #{[100 42]}
             ; below looks just like the datomic api
             (d/q '[:find ?e ?k
                    :in $ ?id ?e
                    :where
                    [?e :identity/key ?k]]
                  db
                  100))))

    (testing "attribute"
      ; below looks just like the datomic api
      (is (= 42 (d/attribute db 100))))

    (testing "transact and resolve ids"
      ; below looks just like the datomic api
      (let [new-entity-id (d/tempid :db.part/user)
            result @(d/transact conn [{:db/id        new-entity-id
                                       :identity/key :foo}])]
        (is (= #{:db-before :db-after :tx-result}
               (set (keys @(d/transact conn [{:identity/key :foo}]))))
            "mock transact delegate returned the results")
        (is (= 123 (d/resolve-tempid (:db-after result) (:tempids result) new-entity-id))
            "tempid clone returns mock delegate result")))))

(deftest bad-data
  (testing "invalid app context"
    (let [app-context {:invalid "value"}
          conn {::clone/app     :basic-tests
                ::clone/UNSAFE! fake-conn
                ::user          app-context}]
      (is (thrown? ExceptionInfo (d/db conn))
          "cloned fn call fails due to spec checking the app context in the wrapped data"))))

;;;; :middleware-test multi-methods ;;;;

; use the ::test-app-context for :middleware-tests as well
(defmethod clone/with-app-context :middleware-tests [_] ::test-app-context)

; notice that all these inteceptor chains include an extra logger interceptor

(defmethod d/db-context :middleware-tests [conn]
  {::chain/queue [(middleware/logger (::middleware/logger-data conn) 'd/db)
                  (db-delegate conn)]})

(defmethod d/pull-context :middleware-tests [db pattern eid]
  {::chain/queue [(middleware/logger (::middleware/logger-data db) 'd/pull pattern eid)
                  (pull-delegate db pattern eid)]})

(defmethod d/query-context :middleware-tests
  [& args]
  (let [db (clone/context-from-args args)]
    {::chain/queue [(middleware/logger (::middleware/logger-data db) 'd/q (first args))
                    (query-delegate (first args) (rest args))]}))

(defmethod d/attribute-context :middleware-tests
  [db attrid]
  {::chain/queue [(middleware/logger (::middleware/logger-data db) 'd/attribute attrid)
                  (attribute-delegate db)]})

(defmethod d/transact-context :middleware-tests [conn data]
  {::chain/queue [(middleware/logger (::middleware/logger-data conn) 'd/transact data)
                  (transact-delegate conn data)]})

(defmethod d/resolve-tempid-context :middleware-tests [db tempids id]
  {::chain/queue [(middleware/logger (::middleware/logger-data db) 'd/resolve-tempid tempids id)
                  (resolve-tempid-delegate db tempids id)]})

(deftest stateful-middleware
  (let [app-context {::username "Dave"}
        ; must use a vector for :invocations so that conj (in middleware/logger) appends at end
        log-atom (atom {:invocations []})
        conn {::clone/app              :middleware-tests
              ::clone/UNSAFE!          fake-conn
              ::user                   app-context
              ::middleware/logger-data log-atom}

        ; start ns fn calls, this is how code in app namespaces will look
        ; notice how datomic invocations below here look identical to datomic.api calls
        db (d/db conn)]

    (middleware/start-logger-group conn :reads)

    (d/pull db '[*] 1)
    (d/pull db '[*] 1)
    (d/pull db '[*] 1)
    (d/q '[:find ?e ?k
           :in $ ?e
           :where
           [?e :identity/key ?k]]
         db
         100)
    (d/attribute db 100)
    (middleware/start-logger-group conn :writes)
    (let [new-entity-id (d/tempid :db.part/user)
          result @(d/transact conn [{:db/id        new-entity-id
                                     :identity/id  (d/squuid)
                                     :identity/key :foo}])
          new-entity-id (d/resolve-tempid (:db-after result) (:tempids result) new-entity-id)]

      (d/pull db '[*] new-entity-id))

    ; finish namespace fn calls

    (is (= '[d/db d/pull d/pull d/pull d/q d/attribute d/transact d/resolve-tempid d/pull]
           (->> @log-atom
                :invocations
                (mapv :fn)))
        "logger interceptor recorded all api calls, including the db call")

    (let [summary (middleware/summarise-log conn :groups-totals)]
      ;(pprint summary)
      (is (= [:reads :writes] (mapv :group summary))
          "two groups recorded")
      (is (= [{:fn 'd/pull :count 3}
              {:fn 'd/q :count 1}
              {:fn 'd/attribute :count 1}]
             (->> (first summary)
                  :invocations
                  (mapv #(select-keys % [:fn :count]))))
          "first group matches calls above, excluding d/db")
      (is (= [{:fn 'd/transact :count 1}
              {:fn 'd/resolve-tempid :count 1}
              {:fn 'd/pull :count 1}]
             (->> (last summary)
                  :invocations
                  (mapv #(select-keys % [:fn :count]))))
          "second group matches calls above"))))

