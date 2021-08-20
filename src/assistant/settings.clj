(ns assistant.settings
  (:require [clojure.edn :as edn]))

(def config (edn/read-string (slurp "config.edn")))
(def secrets (edn/read-string (slurp "secrets.edn")))
