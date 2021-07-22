(ns assistant.state 
  (:require [clojure.edn :as edn]))

(def state (atom nil))
(def config (-> "config.edn" slurp edn/read-string))
