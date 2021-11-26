(ns assistant.core
  (:require [clojure.core.async :as async :refer [chan go]]
            [clojure.tools.logging :as log]
            [assistant.commands :refer [discord-commands]]
            [assistant.events :refer [handler]]
            [discljord.connections :refer [connect-bot! disconnect-bot!]]
            [discljord.events :refer [message-pump!]]
            [discljord.messaging :refer [bulk-overwrite-global-application-commands!
                                         get-current-application-information! start-connection! stop-connection!]]))

(defn -main [& [token]]
  (let [event-ch (chan)
        conn-ch (connect-bot! token event-ch
                              :intents #{})
        msg-ch (start-connection! token)]
    (log/info :info "Application commands:"
              @(bulk-overwrite-global-application-commands! msg-ch
                                                            (:id @(get-current-application-information! msg-ch))
                                                            discord-commands))
    (message-pump! event-ch #(go (handler msg-ch %1 %2)))
    (stop-connection! msg-ch)
    (disconnect-bot! conn-ch)
    (async/close! event-ch)))
