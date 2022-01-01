(ns assistant.core
  (:gen-class)
  (:require [clojure.core.async :as async :refer [chan go]]
            [clojure.edn :as edn]
            [assistant.commands :refer [discord-commands]]
            [assistant.events :refer [handler]]
            [com.brunobonacci.mulog :as u]
            [discljord.connections :refer [connect-bot! disconnect-bot!]]
            [discljord.events :refer [message-pump!]]
            [discljord.messaging :refer [bulk-overwrite-global-application-commands!
                                         bulk-overwrite-guild-application-commands! get-current-application-information!
                                         start-connection! stop-connection!]]))

(set! *warn-on-reflection* true)

(defonce stop (u/start-publisher! {:type :console}))

(defn -main [& configs]
  (let [config (apply merge-with into (map (comp edn/read-string slurp) configs))
        event-ch (chan)
        conn-ch (connect-bot! (:token config) event-ch
                              :intents #{})
        msg-ch (start-connection! (:token config))
        id (:id @(get-current-application-information! msg-ch))]
    (u/log ::global-commands-set :commands @(bulk-overwrite-global-application-commands! msg-ch id discord-commands))
    (if-let [guild (:guild config)]
      (u/log ::guild-commands-set
             :commands @(bulk-overwrite-guild-application-commands! msg-ch id guild discord-commands)
             :guild guild))
    (message-pump! event-ch #(go (handler msg-ch %1 %2)))
    (stop-connection! msg-ch)
    (disconnect-bot! conn-ch)
    (async/close! event-ch)))

(-main "config.edn" "secrets.edn")