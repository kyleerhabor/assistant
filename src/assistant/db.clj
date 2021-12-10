(ns assistant.db
  (:refer-clojure :exclude [read])
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [datascript.core :as d]
            [datascript.transit :as dt]))

(defn write
  "Writes to the database file."
  [db]
  (with-open [stream (io/output-stream "db.json")]
    (dt/write-transit db stream)))

(defonce conn (d/conn-from-db (let [file (io/file "db.json")]
                                (when-not (.exists file)
                                  (log/info "Database file does not exist. Creating...")
                                  (.createNewFile file)
                                  (write (d/empty-db)))
                                (with-open [stream (io/input-stream file)]
                                  (dt/read-transit stream)))))

(add-watch conn :conn (fn [_ _ _ db] (write db)))
