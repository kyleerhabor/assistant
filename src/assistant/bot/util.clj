(ns assistant.bot.util
  (:require
    [clojure.core.async :as async :refer [chan]]
    [assistant.config :as config]
    [discljord.connections :refer [connect-bot! disconnect-bot!]]
    [discljord.messaging :refer [start-connection! stop-connection!]]))

(defn connect
  "Connects an establishes a connection to Discord, returning a map of channels."
  [config]
  (let [event-ch (chan (or (:bot/event-buffer-size config) config/event-buffer-size))
        conn-ch (connect-bot! (:bot/token config) event-ch ; I've got the conch!
                  :intents #{})
        msg-ch (start-connection! (:bot/token config))]
    {:event-ch event-ch
     :conn-ch conn-ch
     :msg-ch msg-ch}))

(defn disconnect [{:keys [msg-ch conn-ch event-ch]}]
  (stop-connection! msg-ch)
  (disconnect-bot! conn-ch)
  (async/close! event-ch))
