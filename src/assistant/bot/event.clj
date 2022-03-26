(ns assistant.bot.event
  (:require
    [clojure.core.async :refer [<! go]]
    [clojure.pprint :refer [pprint]]
    [assistant.i18n :refer [translate]]
    [assistant.bot.interaction :refer [discord-commands discord-guild-commands commands guild-commands]]
    [discljord.messaging :refer [bulk-overwrite-global-application-commands! bulk-overwrite-guild-application-commands!]]
    [discljord.messaging.specs :as ds.ms]
    [strife.core :as strife :refer [find-command]]))

(defmulti handle
  "Handler for Discord API events."
  (fn [_ type _ _] type))

(defmethod handle :interaction-create
  [conn _ inter options]
  (let [command (partial find-command inter)
        res (some-> (or (command commands) (command (get guild-commands (:guild-id inter))))
              (update-in [:out :options] strife/mapify))]
    (if res
      ((strife/runner res) conn inter options))))

(defmethod handle :ready
  [conn _ data _]
  (println "Bot ready!"))

(defmethod handle :default
  [_ _ _ _])
