(ns assistant.events
  (:require
    [clojure.core.async :refer [<! go]]
    [clojure.set :refer [map-invert]]
    [assistant.i18n :refer [translate]]
    [assistant.interaction :refer [discord-commands discord-guild-commands commands guild-commands]]
    [discljord.messaging :refer [bulk-overwrite-global-application-commands! bulk-overwrite-guild-application-commands!]]
    [discljord.messaging.specs :as ds.ms]))

(defmulti handler
  "Handler for Discord API events."
  (fn [_ type _ _] type))

(def command-types (map-invert ds.ms/command-types))

(defn which-command [commands {{:keys [type]} :data} names]
  (get-in ((command-types type) commands) names))

(defn find-command [{:keys [guild-id]
                     :as interaction} names]
  (or (if-let [gcmds (guild-commands guild-id)]
        (which-command gcmds interaction names)) (which-command commands interaction names)))

(defmethod handler :interaction-create
  [conn _ interaction options]
  (let [options (assoc options :translator (partial translate (:locale interaction)))]
    (if-let [name (:name (:interaction (:message interaction)))]
      (let [command (find-command interaction [name])]
        ((:components command) conn interaction options))
      (let [{:keys [names opts]} (loop [names []
                                        options [(assoc (:data interaction) :type 1)]]
                                   (let [option (first options)]
                                     ;; if sub-command or sub-command group
                                     (if (#{1 2} (:type option))
                                       (recur (conj names (:name option)) (:options option))
                                       {:names names
                                        :opts options})))
            ;; Update interaction to include the options that only matter.
            interaction (assoc-in interaction [:data :options] (reduce (fn [m opt]
                                                                         (assoc m (:name opt) opt)) {} opts))
            command (find-command interaction names)
            opt (first opts)
            call (if (:focused opt)
                   (:autocomplete (first (filter (comp (partial = (:name opt)) :name) (:options command))))
                   (:fn command))]
        (call conn interaction options)))))

(defmethod handler :ready
  [conn _ data _]
  (let [app-id (:id (:application data))]
    (go
      (clojure.pprint/pprint (<! (bulk-overwrite-global-application-commands! conn app-id discord-commands)))
      (run! (comp #(go (clojure.pprint/pprint (<! %))) (partial apply bulk-overwrite-guild-application-commands! conn app-id)) discord-guild-commands))))

(defmethod handler :default
  [_ _ _ _])
