(ns assistant.db
  (:import [java.time Period LocalDate LocalDateTime ZonedDateTime OffsetTime Instant OffsetDateTime ZoneId DayOfWeek
            LocalTime Month Duration Year YearMonth])
  (:refer-clojure :exclude [read])
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [cognitect.transit :as transit]
            [datascript.core :as d]
            [datascript.transit :as dt]
            [time-literals.read-write :as tl.rw]))

;; Borrowed from pipeline-transit (https://github.com/Motiva-AI/pipeline-transit) since the definitions were private.
(def time-classes
  {'period Period
   'date LocalDate
   'date-time LocalDateTime
   'zoned-date-time ZonedDateTime
   'offset-time OffsetTime
   'instant Instant
   'offset-date-time OffsetDateTime
   'time LocalTime
   'duration Duration
   'year Year
   'year-month YearMonth
   'zone ZoneId
   'day-of-week DayOfWeek
   'month Month})

(def time-write-handlers
  (reduce-kv #(assoc %1 %3 (transit/write-handler (str "time/" (name %2)) str)) {} time-classes))

(def time-read-handlers
  (reduce-kv #(assoc %1 (str "time/" (name %2)) (transit/read-handler %3)) {} tl.rw/tags))

(def write-handlers
  (merge dt/write-handlers time-write-handlers))

(def read-handlers
  (merge dt/read-handlers time-read-handlers))

(def schema {})

(defn write
  "Writes to the database file."
  [db]
  (with-open [stream (io/output-stream "db.json")]
    (transit/write (transit/writer stream :json {:handlers write-handlers}) db)))

(defn create
  "Creates the database file if it doesn't exist."
  []
  (let [file (io/file "db.json")]
    (when-not (.exists file)
      (log/info "Database file does not exist. Creating...")
      (.createNewFile file)
      (write (d/empty-db schema)))))

(defn read
  "Reads the database file."
  []
  (with-open [stream (io/input-stream "db.json")]
    (transit/read (transit/reader stream :json {:handlers read-handlers}))))

(defonce conn (d/conn-from-db (do (create)
                                  (read))))

(d/listen! conn :conn (comp write :db-after))

(comment
  ;; Update schema
  (d/conn-from-datoms (d/datoms @conn :eavt) schema))
