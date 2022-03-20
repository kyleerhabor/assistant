(ns assistant.db
  (:require [datalevin.core :as d]))

(def schema {})

(def conn (d/get-conn "./db" schema))
