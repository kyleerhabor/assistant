(ns assistant.db
  (:refer-clojure :exclude [read])
  (:require [clojure.java.io :as io]
            [datascript.core :as d]
            [datascript.transit :as dt]))

(defn read []
  ;; TODO: Create the file with a blank database if it doesn't exist.
  (with-open [file (io/input-stream "db.json")]
    (dt/read-transit file)))

(defn write [db]
  (with-open [file (io/output-stream "db.json")]
    (dt/write-transit db file)))

(defn transact
  "Runs a transaction on the database. Intended for operations that only need to read and write once."
  [data]
  (write (:db-after (d/transact! (d/conn-from-db (read))
                                 data))))
