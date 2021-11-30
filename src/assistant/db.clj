(ns assistant.db
  (:refer-clojure :exclude [read])
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [datascript.core :as d]
            [datascript.transit :as dt]))

(defn write [db]
  (with-open [stream (io/output-stream "db.json")]
    (dt/write-transit db stream)))

(defn read []
  (with-open [stream (io/input-stream (let [file (io/file "db.json")]
                                        (when-not (.exists file)
                                          (log/info "Database file does not exist. Creating...")
                                          (.createNewFile file)
                                          (write (d/empty-db)))
                                        file))]
    (dt/read-transit stream)))

(defn transact
  "Runs a transaction on the database. Intended for operations that only need to read and write once."
  [data]
  (write (:db-after (d/transact! (d/conn-from-db (read))
                                 data))))
