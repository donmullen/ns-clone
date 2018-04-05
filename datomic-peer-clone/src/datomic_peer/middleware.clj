(ns ^{:doc "All uses of the Datomic peer API are in this namespace. Allows the consumer namespaces to only require the clone namespace."}
datomic-peer.middleware
  (:require
    [clojure.pprint :refer [pprint]]
    [ns-clone.core :as clone]
    [io.pedestal.interceptor.helpers :as helpers :refer [before around]]
    [datomic.api :as d]
    [clojure.set :as set]))

; fns that return pedestal interceptors that call the Datomic Peer API fns

(defn db-delegate
  "return an interceptor that mocks a datomic d/db call"
  [conn]
  (before :db-delegate (fn [context]
                         (let [wrapped-db-value (assoc conn ::clone/UNSAFE! (d/db (::clone/UNSAFE! conn)))]
                           (assoc context ::clone/result wrapped-db-value)))))

(defn pull-delegate
  "return an interceptor that mocks a datomic d/pull call"
  [db expr eid]
  (before :pull-delegate (fn [context]
                           (assoc context ::clone/result (d/pull (::clone/UNSAFE! db) expr eid)))))

(defn query-delegate
  [& inputs]
  (before :query-delegate (fn [context]
                            ; need to lift the wrapped Datomic db value out of the map before using the args
                            (let [query-args (map (fn lift-db-arg
                                                    [arg]
                                                    (if-let [db (::clone/UNSAFE! arg)]
                                                      db
                                                      arg))
                                                  inputs)
                                  query-result (apply d/q query-args)]
                              (assoc context ::clone/result query-result)))))

(defn attribute-delegate
  [db aid]
  (before :attribute-delegate (fn [context]
                                (assoc context ::clone/result (d/attribute (::clone/UNSAFE! db) aid)))))

(defn transact-delegate
  [conn data]
  (around :transact-delegate
          (fn [context]
            ; add the data to the context, so that other interceptors can decorate it if required
            (assoc context ::transaction-data data))
          (fn [context]
            (let [result @(d/transact (::clone/UNSAFE! conn) (::transaction-data context))
                  wrap-db (fn [db]
                            (assoc conn ::clone/UNSAFE! db))]
              (assoc context ::clone/result
                             ; use an atom to make the result IDeref-able since that is the result shape for d/transact
                             (atom
                               (-> result
                                   (update :db-before wrap-db)
                                   (update :db-after wrap-db))))))))

; TODO transact-async-delegate - requires the same wrapping as transact-delegate, but inside a future

(defn resolve-tempid-delegate
  [db tempids id]
  (before :resolve-tempid-delegate (fn [context]
                                     (assoc context ::clone/result
                                                    (d/resolve-tempid (::clone/UNSAFE! db) tempids id)))))

(defn transaction-annotator
  [annotation]
  (before (fn [context]
            (->> (assoc annotation :db/id (d/tempid :db.part/tx)) ; marks the entity as a transaction annotation
                 (update context ::transaction-data conj)))))

(defn tempid-delegate
  [partition]
  (before :tempid-delegate (fn [context]
                             (assoc context ::clone/result (d/tempid partition)))))

(def squuid-delegate
  (before :tempid-delegate (fn [context]
                             (assoc context ::clone/result (d/squuid)))))


