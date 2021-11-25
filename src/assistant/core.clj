(ns assistant.core
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [assistant.commands :refer [discord-commands]]
            [assistant.events :refer [handler]]
            [assistant.settings :refer [secrets]]
            [discljord.connections :refer [connect-bot! disconnect-bot!]]
            [discljord.events :refer [message-pump!]]
            [discljord.messaging :refer [bulk-overwrite-global-application-commands!
                                         get-current-application-information! start-connection! stop-connection!]]))

(defn -main []
  (let [token (:token secrets)
        event-ch (async/chan 128)
        conn-ch (connect-bot! token event-ch
                              :intents #{})
        msg-ch (start-connection! token)]
    (log/info :info "Application commands:"
              @(bulk-overwrite-global-application-commands! msg-ch
                                                            (:id @(get-current-application-information! msg-ch))
                                                            discord-commands))
    (message-pump! event-ch (partial handler msg-ch))
    (stop-connection! msg-ch)
    (disconnect-bot! conn-ch)
    (async/close! event-ch)))
