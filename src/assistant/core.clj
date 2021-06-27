(ns assistant.core
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [discljord.connections :as connections]
            [discljord.messaging :as messaging]))

(def token (-> "config.edn"
               slurp
               edn/read-string
               :token))

(defn -main
  "Runs the program."
  []
  (let [event-ch (async/chan 100)
        connection-ch (connections/connect-bot! token event-ch
                                                :intents #{:guild-messages})
        message-ch (messaging/start-connection! token)]
    (try
      (loop []
        (let [[type data] (async/<!! event-ch)]
          (when (and (= :message-create type)
                     (-> data :author :bot not)
                     (= "assistant greet" (-> data
                                              :content
                                              string/lower-case
                                              string/triml)))
            (messaging/create-message! message-ch (:channel-id data)
                                       :content (str "Hello, " (-> data :author :username) ".")))
          (when (= :channel-pins-update type)
            (connections/disconnect-bot! connection-ch))
          (when-not (= :disconnect type)
            (recur))))
      (finally
        (messaging/stop-connection! message-ch)
        (async/close! event-ch)))))
