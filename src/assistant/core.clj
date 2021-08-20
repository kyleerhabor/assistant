(ns assistant.core
  (:require [clojure.core.async :as async]
            [assistant.events :refer [handler]]
            [assistant.settings :refer [config secrets]]
            [discljord.connections :refer [connect-bot! disconnect-bot!]]
            [discljord.messaging :refer [bulk-overwrite-global-application-commands!
                                         get-current-application-information!
                                         start-connection!
                                         stop-connection!]]
            [discljord.events :refer [message-pump!]]))

(defn -main []
  (let [event-ch (async/chan 128)
        conn-ch (connect-bot! (:token secrets) event-ch
                              :intents #{})
        msg-ch (start-connection! (:token secrets))]
    (bulk-overwrite-global-application-commands! msg-ch
                                                 (:id @(get-current-application-information! msg-ch))
                                                 (:commands config))
    (message-pump! event-ch (partial handler msg-ch))
    (stop-connection! msg-ch)
    (disconnect-bot! conn-ch)
    (async/close! event-ch)))
