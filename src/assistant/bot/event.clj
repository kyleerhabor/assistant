(ns assistant.bot.event
  (:require
    [clojure.tools.logging :as log]
    [assistant.bot.interaction :refer [commands guild-commands]]
    [assistant.i18n :as i18n]
    [strife.core :as strife :refer [find-command]]))

(defmulti handle
  "Handler for Discord API events."
  (fn [_ type _ _] type))

(defmethod handle :interaction-create
  [conn _ inter options]
  (let [command (partial find-command inter)]
    ;; Probably wrong.
    (if-let [res (or (command commands) (command (get guild-commands (:guild-id inter))))]
      (let [res (update-in res [:out :options] strife/mapify)
            command (strife/runner res)
            inter (assoc-in inter [:data :options] (strife/mapify (:options (:in res))))
            options (assoc options :translator (partial i18n/translate (:locale inter)))]
        (command conn inter options)))))

(defmethod handle :ready
  [_ _ data _]
  (log/info "Bot ready!"))

(defmethod handle :default
  [_ _ _ _])
