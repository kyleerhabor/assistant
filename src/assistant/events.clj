(ns assistant.events
  (:require
    [clojure.core.async :refer [<! go]]
    [assistant.interactions :refer [discord-global-commands discord-guild-commands global-commands guild-commands]]
    [assistant.utils :refer [rpartial]]
    [discljord.messaging :refer [bulk-overwrite-global-application-commands! bulk-overwrite-guild-application-commands!]]))

(defmulti handler
  "Handler for Discord API events."
  (fn [_ type _ _] type))

(defn which-commands [bot-gid interaction-gid]
  (if (and bot-gid (= bot-gid interaction-gid))
    [guild-commands global-commands]
    [global-commands]))

(defmethod handler :interaction-create
  [conn _ interaction {:keys [config]
                       :as options}]
  ;; First let: get all "names" from the interaction: command, subcommand group, and subcommand. Also retrieve the
  ;; options associated with the deepest level.
  (let [commands (which-commands (:bot/guild-id config) (:guild-id interaction))]
    ;; Will succeed on message components.
    (if-let [name (:name (:interaction (:message interaction)))]
      (let [command (some (keyword name) commands)]
        ((:components command) conn interaction options))
      (let [{:keys [names opts]} (loop [names []
                                        options [(assoc (:data interaction) :type 1)]]
                                   (let [option (first options)]
                                     ;; if subcommand or subcommand group
                                     (if (#{1 2} (:type option))
                                       (recur (conj names (keyword (:name option))) (:options option))
                                       {:names names
                                        :opts options})))
            ;; Update interaction to include the options that only matter.
            interaction (assoc-in interaction [:data :options] (reduce (fn [m opt]
                                                                         (assoc m (keyword (:name opt)) opt)) {} opts))
            command (some (rpartial get-in names) commands)
            opt (first opts)
            call (if (:focused opt)
                   (:autocomplete (first (filter (comp (partial = (:name opt)) :name) (:options command))))
                   (:fn command))]
        (call conn interaction options)))))

(defmethod handler :ready
  [conn _ data {:keys [config]}]
  (let [appid (:id (:application data))]
    (go
      (<! (bulk-overwrite-global-application-commands! conn appid discord-global-commands))
      (if-let [gid (:bot/guild-id config)]
        (<! (bulk-overwrite-guild-application-commands! conn appid gid discord-guild-commands))))))

(defmethod handler :default
  [_ _ _ _])
