(ns assistant.interaction
  (:require
    [clojure.core.async :refer [<! >! chan go timeout]]
    [clojure.set :refer [rename-keys]]
    [clojure.string :as str]
    [assistant.config :as cfg]
    [assistant.interaction.anilist :as anilist]
    [assistant.interaction.util :refer [ephemeral image-sizes max-autocomplete-name-length]]
    [assistant.utils :refer [hex->int split-keys truncate]]
    [camel-snake-kebab.core :as csk]
    [cheshire.core :as che]
    [clj-http.client :as http]
    [discljord.cdn :as ds.cdn]
    [discljord.formatting :as ds.fmt]
    [discljord.messaging :refer [bulk-delete-messages! create-interaction-response! delete-message!
                                 delete-original-interaction-response! get-channel! get-channel-messages!]]
    [discljord.messaging.specs :refer [command-option-types interaction-response-types]]
    [discljord.permissions :as ds.perms]
    [graphql-query.core :refer [graphql-query]]
    [tick.core :as tick]))

(def kebab-kw (comp keyword csk/->kebab-case))

(defn avatar-url [user size]
  (ds.cdn/resize (ds.cdn/effective-user-avatar user) size))

(defn nsfw
  "Returns a channel with a boolean indicating whether or not an interaction was run in an NSFW environment."
  [conn interaction]
  (go
    (if-let [cid (:channel-id interaction)]
      (or (:nsfw (<! (get-channel! conn cid))) false)
      false)))

(defn respond
  [conn interaction & args]
  (apply create-interaction-response! conn (:id interaction) (:token interaction) args))

(defn error-data [s]
  {:content s
   :flags ephemeral})

;;; HTTP

(defn request-async [options]
  (let [result (chan)
        options (assoc options :async? true)]
    (http/request options
      #(go (>! result %))
      ;; In the event of an error, we'll just do nothing. `result` will park on take and cause the interaction to
      ;; timeout.
      (constantly nil))
    result))

(defn query-anilist [graphql]
  (go (:data (:body (<! (request-async {:url "https://graphql.anilist.co/"
                                        :method :post
                                        :as :json
                                        :body (che/generate-string {:query graphql})
                                        :content-type :json}))))))

;;; Commands

(defn animanga [conn {{{{id :value} :query} :options} :data
                      :as interaction} {translate :translator}]
  (go
    (let [body (<! (query-anilist (graphql-query {:queries [(anilist/media id)]})))
          media (:Media body)
          adult? (:isAdult media)]
      (respond conn interaction (:channel-message-with-source interaction-response-types)
        :data (cond
                (not media) (error-data (translate :not-found))
                (and adult? (not (<! (nsfw conn interaction)))) (error-data (translate (keyword (str
                                                                                                  "nsfw-"
                                                                                                  (str/lower-case (:type media))))))
                :else {:embeds [(let [cover-image (:coverImage media)
                                      episodes (:episodes media)
                                      chapters (:chapters media)
                                      volumes (:volumes media)
                                      rankings (:rankings media)
                                      score (:averageScore media)
                                      popularity (:popularity media)
                                      source (:source media)
                                      start-date (translate :fuzzy-date (:startDate media))
                                      end-date (translate :fuzzy-date (:endDate media))
                                      links (:externalLinks media)]
                                  {:title (anilist/format-media-title media)
                                   :description (some-> (:description media) anilist/format-media-description)
                                   :url (:siteUrl media)
                                   :thumbnail {:url (:extraLarge cover-image)}
                                   :color (some-> (:color cover-image) hex->int)
                                   :fields (cond-> [{:name (translate :format)
                                                     :value (translate (kebab-kw (:format media)))
                                                     :inline true}
                                                    {:name (translate :status)
                                                     :value (translate (kebab-kw (:status media)))
                                                     :inline true}]
                                             ;; Anime
                                             episodes (conj {:name (translate :episodes)
                                                             :value (translate :interaction.animanga/episodes episodes (:duration media))
                                                             :inline true})

                                             ;; Manga
                                             chapters (conj {:name (translate :chapters)
                                                             :value (translate :interaction.animanga/chapters chapters (:volumes media))
                                                             :inline true})
                                             (and (not chapters) volumes) (conj {:name (translate :volumes)
                                                                                 :value volumes
                                                                                 :inline true})

                                             ;; Others
                                             score (conj {:name (translate :score)
                                                          :value (translate :anilist.media/score score
                                                                   (anilist/media-rank rankings "RATED"))
                                                          :inline true})
                                             popularity (conj {:name (translate :popularity)
                                                               :value (translate :anilist.media/popularity popularity
                                                                        (anilist/media-rank rankings "POPULAR"))
                                                               :inline true})
                                             source (conj {:name (translate :source)
                                                           :value (translate (kebab-kw source))
                                                           :inline true})
                                             (seq start-date) (conj {:name (translate :start-date)
                                                                     :value start-date
                                                                     :inline true})
                                             (seq end-date) (conj {:name (translate :end-date)
                                                                   :value end-date
                                                                   :inline true})
                                             (seq links) (conj {:name (translate :links)
                                                                :value (str/join ", " (map #(ds.fmt/embed-link (:site %) (:url %))
                                                                                        links))}))})]})))))

(defn animanga-autocomplete [conn {{{{query :value} :query} :options} :data
                                   :as interaction} {translate :translator}]
  (go
    (let [body (<! (query-anilist (graphql-query {:queries [(anilist/media-preview query
                                                              {:adult? (if-not (<! (nsfw conn interaction))
                                                                         false)})]})))]
      (respond conn interaction (:application-command-autocomplete-result interaction-response-types)
        :data {:choices (for [media (:media (:Page body))]
                          ;; NOTE: For English, the note should only be capitalized for abbreviations. Unfortunately,
                          ;; way localization is set up forces everything to be capitalized. A minor annoyance that
                          ;; should be fixed in the future.
                          {:name (let [note (str " (" (translate (kebab-kw (:format media))) ")")]
                                   (str (truncate (anilist/media-title (:title media)) (- max-autocomplete-name-length (count note)))
                                     note))
                           :value (:id media)})}))))

(defn avatar
  [conn {{{{user :value} :user
           {size :value
            :or {size (last image-sizes)}} :size} :options
          :as data} :data
         member :member
         :as interaction} _]
  (respond conn interaction (:channel-message-with-source interaction-response-types)
    :data {:content (let [user (or
                                 ;; Get the user from the user argument.
                                 (get (:users (:resolved data)) user)
                                 ;; Get the user who ran the interaction.
                                 (:user (or member interaction)))
                          user-url (avatar-url user size)]
                      (if (:avatar member)
                        (str
                          "User: " user-url "\n"
                          "Server: " (avatar-url member size))
                        user-url))}))

(defn purge [conn {{{{amount :value} :amount} :options} :data
                   cid :channel-id
                   :keys [member]
                   :as interaction} {translate :translator}]
  (go
    (let [respond (partial respond conn interaction (:channel-message-with-source interaction-response-types) :data)]
      (cond
        (not member)
        (respond (error-data (translate :guild-only)))

        (not (ds.perms/has-permission-flag? :manage-messages (:permissions member)))
        (respond (error-data (translate :missing-manage-messages)))

        :else (let [msgs (->> (<! (get-channel-messages! conn cid :limit amount))
                           (filter #(< -14 (tick/days (tick/between (tick/now) (:timestamp %)))))
                           (filter (complement :pinned))
                           (map :id))]
                (if (seq msgs)
                  (if (<! (if (= 1 (count msgs))
                            (delete-message! conn cid (first msgs))
                            (bulk-delete-messages! conn cid msgs)))
                    (when (<! (respond {:content (translate :interaction.purge/success)}))
                      (<! (timeout cfg/purge-timeout))
                      (delete-original-interaction-response! conn (:application-id interaction) (:token interaction)))
                    (respond (error-data (translate :interaction.purge/fail))))
                  (respond (error-data (translate :interaction.purge/none)))))))))

;;; Command exportation (transformation) facilities.

;; The interaction commands. The key is the name and the value contains properties useful for dispatchers (e.g. `:fn`).
;; :options is an array of maps instead of a map of maps since the order matters to Discord.
(def global-commands {:animanga {:fn animanga
                                 :description "Search for an anime or manga."
                                 :options [{:type (:integer command-option-types)
                                            :name "query"
                                            :description "The anime or manga to search for."
                                            :required true
                                            :autocomplete animanga-autocomplete}]}
                      :avatar {:fn avatar
                               :description "Displays a user's avatar."
                               :options [{:type (:user command-option-types)
                                          :name "user"
                                          :description "The user to retrieve the avatar of. Defaults to the user who ran the command."}
                                         {:type (:integer command-option-types)
                                          :name "size"
                                          :description "The maximum size of the avatar. May be lower if size is not available."
                                          :choices (map #(zipmap [:name :value] (repeat %)) image-sizes)}]}})

(def guild-commands {:purge {:fn purge
                             :description "Deletes messages from the channel."
                             :options [{:type (:integer command-option-types)
                                        :name "amount"
                                        :description "The number of messages to remove (may remove less)."
                                        :required true
                                        :min-value 2
                                        :max-value 100}]}})

(def command-keys [:name :description :options :default-permission :type])
(def command-option-keys [:type :name :description :required :choices :options :channel-types :min-value :max-value
                          :autocomplete])

(defn normalize-option [option]
  (let [option (rename-keys option {:channel-types :channel_types
                                    :min-value :min_value
                                    :max-value :max_value})]
    (if (:autocomplete option)
      (assoc option :autocomplete true)
      option)))

(defn normalize-command [command]
  (let [command (rename-keys command {:default-permission :default_permission})]
    (if (:options command)
      (update command :options (partial map normalize-option))
      command)))

(defn normalize [m name]
  (dissoc (assoc m :name name) :fn :components))

(defn transform-subs [subcommands]
  (reduce-kv (fn [coll name option]
               (let [[option subs] (split-keys (normalize-option (normalize option name)) command-option-keys)]
                 (conj coll (if (seq subs)
                              (assoc option
                                :type (:sub-command-group command-option-types)
                                :options (transform-subs subs))
                              (assoc option :type (:sub-command command-option-types)))))) [] subcommands))

(defn transform [commands]
  (reduce-kv (fn [coll name command]
               (let [[command subs] (split-keys (normalize-command (normalize command name)) command-keys)]
                 (conj coll (if (seq subs)
                              ;; If there are sub-commands, there are no options to begin with. Therefore, it's safe to
                              ;; `assoc` here.
                              (assoc command :options (transform-subs subs))
                              command)))) [] commands))


(def discord-global-commands (transform global-commands))
(def discord-guild-commands (transform guild-commands))

#_(defn overwrite-application-commands [conn]
    (go
      (<! (bulk-overwrite-global-application-commands! conn appid discord-global-commands))
      (if (seq discord-guild-commands)
        (<! (bulk-overwrite-guild-application-commands! conn appid gid discord-guild-commands)))))

(comment
  (def translate (partial assistant.i18n/translate :en-US)))
