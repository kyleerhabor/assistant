(ns assistant.core
  (:require
    [assistant.config :as cfg]
    [assistant.bot :as bot]))

(defn -main [& configs]
  (let [config (apply cfg/read-config configs)]
    (bot/run-bot config)))
