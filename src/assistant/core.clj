(ns assistant.core
  (:require
    [clojure.core.async :as async :refer [chan go]]
    [clojure.edn :as edn]
    [assistant.events :refer [handler]]
    [discljord.connections :refer [connect-bot! disconnect-bot!]]
    [discljord.events :refer [message-pump!]]
    [discljord.messaging :refer [start-connection! stop-connection!]]))

(defn read-config
  [files]
  (apply merge-with into (map (comp edn/read-string slurp) files)))

(defn -main [& configs]
  (let [config (read-config configs)
        event-ch (chan (or (:bot/event-channel-size config) 128))
        conn-ch (connect-bot! (:bot/token config) event-ch
                  :intents #{})
        msg-ch (start-connection! (:bot/token config))]
    (message-pump! event-ch #(go (handler msg-ch %1 %2 {:config config})))
    (stop-connection! msg-ch)
    (disconnect-bot! conn-ch)
    (async/close! event-ch)))
