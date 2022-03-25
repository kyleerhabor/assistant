(ns assistant.bot.event
  (:require
    [clojure.core.async :refer [<! go]]
    [clojure.pprint :refer [pprint]]
    [clojure.set :refer [map-invert]]
    [assistant.i18n :refer [translate]]
    [assistant.bot.interaction :refer [discord-commands discord-guild-commands commands guild-commands]]
    [discljord.messaging :refer [bulk-overwrite-global-application-commands! bulk-overwrite-guild-application-commands!]]
    [discljord.messaging.specs :as ds.ms]))

(defmulti handle
  "Handler for Discord API events."
  (fn [_ type _ _] type))

(def command-types (map-invert ds.ms/command-types))

(defn which-command [commands {{:keys [type]} :data} names]
  (get-in ((command-types type) commands) names))

(defn find-command [{:keys [guild-id]
                     :as interaction} names]
  (or
    (if-let [gcmds (guild-commands guild-id)]
      (which-command gcmds interaction names))
    (which-command commands interaction names)))

(defmethod handle :interaction-create
  [conn _ interaction options]
  (pprint interaction)
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
            interaction (assoc-in interaction [:data :options] (reduce (fn [m opt]
                                                                         (assoc m (keyword (:name opt)) opt)) {} opts))
            command (find-command interaction names)
            opt (first opts)
            call (if (:focused opt)
                   (:autocomplete (first (filter (comp (partial = (:name opt)) :name) (:options command))))
                   (:fn command))]
        (call conn interaction options)))))

(defmethod handle :ready
  [conn _ data _]
  (println "Bot ready!"))

(defmethod handle :default
  [_ _ _ _])

#_(def inter {:application-id "856158596344709130",
              :locale "en-US",
              :guild-locale "en-US",
              :type 2,
              :guild-id "939382862401110058",
              :member
              {:premium-since nil,
               :deaf false,
               :is-pending false,
               :nick nil,
               :permissions "2199023255551",
               :pending false,
               :roles [],
               :joined-at "2022-02-05T04:52:07.382000+00:00",
               :avatar nil,
               :user
               {:username "Klay",
                :public-flags 512,
                :id "345539839393005579",
                :discriminator "0427",
                :avatar "a_6bf2fcc3fa706e44b4062652823dbc88"},
               :communication-disabled-until nil,
               :mute false},
              :token
              "aW50ZXJhY3Rpb246OTU3MDI4MzQ5MTMyMjc5OTA4OlhOOHRScjJrYndWT2xiOU92dkVrMm5ObmZwZmVrUDZZS1VEaWxWaHNLcUNZN3JhM3hDSTh6NWNrempvYlcyNmE5QUdnWFh3MWFhMFV1ZnZ4Y25LMjdhRFVYbzcyQmg3VVZoUTVYOHhScXNoclg3TkhkS3hKT25udWFTZEQ1bWVh",
              :id "957028349132279908",
              :channel-id "940331535196901386",
              :version 1,
              :data
              {:type 1,
               :resolved
               {:users
                {"856158596344709130"
                 {:username "Assistant",
                  :public-flags 0,
                  :id "856158596344709130",
                  :discriminator "8748",
                  :bot true,
                  :avatar "d06104d4c34ab3fe503f335cf2303510"},
                 "345539839393005579"
                 {:username "Klay",
                  :public-flags 512,
                  :id "345539839393005579",
                  :discriminator "0427",
                  :avatar "a_6bf2fcc3fa706e44b4062652823dbc88"}},
                :members
                {"856158596344709130"
                 {:premium-since nil,
                  :is-pending false,
                  :nick nil,
                  :permissions "1071698538049",
                  :pending false,
                  :roles ["940331621922521131" "939540617019674635"],
                  :joined-at "2022-02-05T15:18:58.981000+00:00",
                  :avatar nil,
                  :communication-disabled-until nil},
                 "345539839393005579"
                 {:premium-since nil,
                  :is-pending false,
                  :nick nil,
                  :permissions "2199023255551",
                  :pending false,
                  :roles [],
                  :joined-at "2022-02-05T04:52:07.382000+00:00",
                  :avatar nil,
                  :communication-disabled-until nil}}},
               :options
               [{:type 1,
                 :options
                 [{:value "856158596344709130", :type 6, :name "user"}
                  {:value "345539839393005579", :type 6, :name "search"}],
                 :name "view"}],
               :name "relation",
               :id "944792525699416114",
               :guild-id "939382862401110058"}})
#_(def commands [{:name "relation"
                  :description "Relation graphing facilities."
                  :options [{:fn identity
                             :type (:sub-command command-option-types)
                             :name "create"
                             :description "Creates a relation."
                             :options [{:type (:user command-option-types)
                                        :name "source"
                                        :description "The user to start from."
                                        :required? true}
                                       {:type (:string command-option-types)
                                        :name "notes"
                                        :description "The details about the relation."
                                        :required? true}
                                       {:type (:string command-option-types)
                                        :name "title"
                                        :description "What the relation is about."}
                                       {:type (:user command-option-types)
                                        :name "target"
                                        :description "The user to end at."}]}
                            {:fn identity
                             :type (:sub-command command-option-types)
                             :name "view"
                             :description "Displays relations."
                             :options [{:type (:user command-option-types)
                                        :name "user"
                                        :description "The user to look up."}
                                       {:type (:user command-option-types)
                                        :name "search"
                                        :description "The text to match for titles and notes."}]}]}])

