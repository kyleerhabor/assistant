(ns assistant.core
  (:require
    [clojure.core.async :as async :refer [chan go]]
    [clojure.edn :as edn]
    [assistant.commands :refer [discord-commands]]
    [assistant.events :refer [handler]]
    [com.brunobonacci.mulog :as u]
    [discljord.connections :refer [connect-bot! disconnect-bot!]]
    [discljord.events :refer [message-pump!]]
    [discljord.messaging :refer [bulk-overwrite-global-application-commands! bulk-overwrite-guild-application-commands!
                                 get-current-application-information! start-connection! stop-connection!]]))

(defonce stop (u/start-publisher! {:type :multi
                                   :publishers [{:type :console}
                                                {:type :simple-file
                                                 :filename "./logs/mulog.log"}]}))

(defn read-config
  [files]
  (apply merge-with into (map (comp edn/read-string slurp) files)))

(defn -main [& configs]
  (let [config (read-config configs)
        event-ch (chan (or (:bot/event-channel-size config) 128))
        conn-ch (connect-bot! (:bot/token config) event-ch
                  :intents #{})
        msg-ch (start-connection! (:bot/token config))
        id (:id @(get-current-application-information! msg-ch))]
    (u/log ::global-commands-set :commands @(bulk-overwrite-global-application-commands! msg-ch id discord-commands))
    (if-let [gid (:bot/guild-id config)]
      (u/log ::guild-commands-set
        ;; TODO: Only overwrite commands registered for guilds.
        :commands @(bulk-overwrite-guild-application-commands! msg-ch id gid discord-commands)
        :guild gid))
    (message-pump! event-ch #(go (handler msg-ch %1 %2)))
    (stop-connection! msg-ch)
    (disconnect-bot! conn-ch)
    (async/close! event-ch)))
