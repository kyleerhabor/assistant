(ns assistant.db
  (:require [xtdb.api :as xt]
            [compact-uuids.core :as uuid]))

(defn kv-store [dir]
  {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store
              :db-dir dir}})

(defonce node (xt/start-node {:xtdb/tx-log (kv-store "db/tx-log")
                              :xtdb/document-store (kv-store "db/doc-store")
                              :xtdb/index-store (kv-store "db/index-store")}))

(defn id []
  (uuid/str (java.util.UUID/randomUUID)))
