(ns assistant.core
  (:require [assistant.state :refer [state config]]
            [assistant.events :refer [handler]]
            [clojure.core.async :as async]
            [discljord.events :as events]
            [discljord.connections :as connections]
            [discljord.messaging :as messaging]))

(defn -main
  "Runs the program."
  []
  (let [event-channel (async/chan 100)
        token (:token config)
        connection-channel (connections/connect-bot! token event-channel
                                                     :intents #{:guilds :guild-messages})
        message-channel (messaging/start-connection! token)]
    (reset! state {:connection connection-channel
                   :event event-channel
                   :message message-channel})
    (events/message-pump! event-channel handler)
    (messaging/stop-connection! message-channel)
    (async/close! event-channel)))
