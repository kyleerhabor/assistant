(ns assistant.settings
  (:require [clojure.edn :as edn]))

(def deps (edn/read-string (slurp "deps.edn")))
(def secrets (edn/read-string (slurp "secrets.edn")))
