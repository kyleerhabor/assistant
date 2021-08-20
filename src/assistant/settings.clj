(ns assistant.settings
  (:require [clojure.edn :as edn]))

(def config (edn/read-string (slurp "config.edn")))
(def deps (edn/read-string (slurp "deps.edn")))
(def secrets (edn/read-string (slurp "secrets.edn")))
