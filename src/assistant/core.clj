(ns assistant.core
  (:require [clojure.core.async :as async]
            [assistant.commands :refer [discord-commands]]
            [assistant.events :refer [handler]]
            [assistant.settings :refer [secrets]]
            [discljord.connections :refer [connect-bot! disconnect-bot!]]
            [discljord.events :refer [message-pump!]]
            [discljord.events.middleware :refer [ignore-bot-messages]]
            [discljord.messaging :refer [bulk-overwrite-global-application-commands!
                                         get-current-application-information!
                                         start-connection!
                                         stop-connection!]]))

(defn -main []
  (let [token (:token secrets)
        event-ch (async/chan 128 ignore-bot-messages)
        conn-ch (connect-bot! token event-ch
                              :intents #{})
        msg-ch (start-connection! token)]
    (bulk-overwrite-global-application-commands! msg-ch
                                                 (:id @(get-current-application-information! msg-ch))
                                                 discord-commands)
    (message-pump! event-ch (partial handler msg-ch))
    (stop-connection! msg-ch)
    (disconnect-bot! conn-ch)
    (async/close! event-ch)))
