(ns assistant.dev
  (:require
    [assistant.core :refer [-main]]
    [assistant.config :as config]
    [assistant.bot.interaction :refer [discord-commands discord-guild-commands]]
    [assistant.bot.util :refer [connect disconnect]]
    [discljord.messaging :refer [bulk-overwrite-global-application-commands! bulk-overwrite-guild-application-commands!
                                 get-current-application-information!]]))

(set! *warn-on-reflection* true)

(defn upload-interactions [& configs]
  (let [config (apply config/read-config configs)
        {conn :msg-ch
         :as chans} (connect config)
        appid (:id @(get-current-application-information! conn))]
    (println "Global:" @(bulk-overwrite-global-application-commands! conn appid discord-commands))
    (doseq [[gid commands] discord-guild-commands]
      (println (str "Guild (" gid "): "
                 @(bulk-overwrite-guild-application-commands! conn appid gid commands))))
    (disconnect chans)))

(comment
  ;; Runs the bot.
  (-main "config.edn" "secrets.edn")

  ;; Update interactions
  (upload-interactions "secrets.edn"))
