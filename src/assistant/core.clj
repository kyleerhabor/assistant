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

(defonce stop (u/start-publisher! {:type :multi
                                   :publishers [{:type :console}
                                                {:type :simple-file
                                                 :filename "./logs/mulog.log"}]}))

(defn -main [& configs]
  (let [{:keys [token guild]} (apply merge-with into (map (comp edn/read-string slurp) configs))
        event-ch (chan)
        conn-ch (connect-bot! token event-ch
                              :intents #{})
        msg-ch (start-connection! token)
        id (:id @(get-current-application-information! msg-ch))]
    (u/log ::global-commands-set :commands @(bulk-overwrite-global-application-commands! msg-ch id discord-commands))
    (and guild (u/log ::guild-commands-set
                      :commands @(bulk-overwrite-guild-application-commands! msg-ch id guild discord-commands)
                      :guild guild))
    (message-pump! event-ch #(go (handler msg-ch %1 %2)))
    (stop-connection! msg-ch)
    (disconnect-bot! conn-ch)
    (async/close! event-ch)))



(comment
  (-main "config.edn" "secrets.edn"))
