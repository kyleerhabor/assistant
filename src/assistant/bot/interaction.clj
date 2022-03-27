(ns assistant.bot.interaction
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [assistant.config :as config]
    [assistant.bot.util :refer [connect disconnect]]
    [assistant.bot.interaction.anilist :as anilist]
    [assistant.bot.interaction.util :refer [ephemeral image-sizes max-autocomplete-name-length]]
    [assistant.util :refer [hex->int pause rpartial truncate]]
    [aleph.http :as http]
    [camel-snake-kebab.core :as csk]
    [discljord.cdn :as ds.cdn]
    [discljord.formatting :as ds.fmt]
    [discljord.messaging :refer [bulk-delete-messages! bulk-overwrite-global-application-commands!
                                 bulk-overwrite-guild-application-commands! create-interaction-response!
                                 delete-message! delete-original-interaction-response! get-channel!
                                 get-channel-messages! get-current-application-information!]]
    [discljord.messaging.specs :refer [command-option-types interaction-response-types]]
    [discljord.permissions :as ds.perms]
    [graphql-query.core :refer [graphql-query]]
    [manifold.deferred :as mfd :refer [let-flow]]
    [strife.core :as strife]
    [tick.core :as tick]))

(def kebab-kw (comp keyword csk/->kebab-case))

(defn avatar-url [user size]
  (ds.cdn/resize (ds.cdn/effective-user-avatar user) size))

(defn nsfw
  "Returns a boolean or a deferred boolean indicating whether or not an interaction was run in an NSFW environment."
  [conn inter]
  (if-let [cid (:channel-id inter)]
    (mfd/chain (get-channel! conn cid) #(or (:nsfw %) false))
    false))

(defn respond
  [conn inter & args]
  (apply create-interaction-response! conn (:id inter) (:token inter) args))

(defn error [content]
  {:content content
   :flags ephemeral})

;;; Commands

(defn animanga [conn {{{{id :value} "query"} :options} :data
                      :as inter} {translate :translator}]
  (let-flow [data (anilist/query (graphql-query {:queries [(anilist/media id)]}))
             media (:Media data)
             nsfw (if (:isAdult media)
                    (nsfw conn inter))]
    (respond conn inter (:channel-message-with-source interaction-response-types)
      :data (cond
              (not media) (error (translate :not-found))
              (and (:isAdult media) (not nsfw)) (error (translate (keyword (str "nsfw-" (str/lower-case (:type media))))))
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
                                                                                      links))}))})]}))))

(defn animanga-autocomplete [conn {{{{query :value} "query"} :options} :data
                                   :as inter} {translate :translator}]
  (let-flow [nsfw? (nsfw conn inter)
             data (anilist/query (graphql-query {:queries [(anilist/media-preview query {:adult? (if-not nsfw?
                                                                                                   false)})]}))]
    (respond conn inter (:application-command-autocomplete-result interaction-response-types)
      :data {:choices (for [media (:media (:Page data))]
                        ;; NOTE: For English, the note should only be capitalized for abbreviations. Unfortunately, way
                        ;; localization is set up forces everything to be capitalized. A minor annoyance that should be
                        ;; fixed in the future.
                        {:name (let [note (str " (" (translate (kebab-kw (:format media))) ")")]
                                 (str (truncate (anilist/media-title (:title media))
                                        (- max-autocomplete-name-length (count note)))
                                   note))
                         :value (:id media)})})))

(defn avatar [conn {{{{user :value} "user"
                      {size :value
                       :or {size (last image-sizes)}} "size"} :options
                     :as data} :data
                    :as inter
                    :keys [member]} _]
  (respond conn inter (:channel-message-with-source interaction-response-types)
    :data {:content (let [user (or
                                 ;; Get the user from the user argument.
                                 (get (:users (:resolved data)) user)
                                 ;; Get the user who ran the interaction.
                                 (:user (or member inter)))
                          user-url (avatar-url user size)]
                      (if (:avatar member)
                        (str
                          "User: " user-url "\n"
                          "Server: " (avatar-url member size))
                        user-url))}))

(defn purge [conn {{{{amount :value} "amount"} :options} :data
                   cid :channel-id
                   :keys [member]
                   :as inter} {translate :translator
                               :keys [config]}]
  (let [respond (partial respond conn inter (:channel-message-with-source interaction-response-types) :data)]
    (cond
      (not member)
      (respond (error (translate :guild-only)))

      (not (ds.perms/has-permission-flag? :manage-messages (:permissions member)))
      (respond (error (translate :missing-manage-messages)))

      ;; Instead of fetching up to the number of messages to purge, this will fetch the maximum amount (100), then apply
      ;; the filters, and take up to the number of messages requested to purge. This is because a collection of messages
      ;; could be filtered and return less than what the user requested, which may be annoying.
      :else (let-flow [msgs (get-channel-messages! conn cid {:limit 100})
                       ids (->> msgs
                             (filter #(< -14 (tick/days (tick/between (tick/now) (:timestamp %)))))
                             (filter (complement :pinned))
                             (take amount)
                             (map :id))]
              (if (seq ids)
                (let-flow [deleted? (if (= 1 (count ids))
                                      (delete-message! conn cid (first ids))
                                      (bulk-delete-messages! conn cid ids))]
                  (if deleted?
                    (mfd/chain (respond {:content (translate :interaction.purge/success)})
                      (fn [_] (pause (or (:timeout (:purge (:chat-input (:global (:bot/commands config))))) config/purge-timeout)))
                      (fn [_] (delete-original-interaction-response! conn (:application-id inter) (:token inter))))
                    (respond (error (translate :interaction.purge/fail)))))
                (respond (error (translate :interaction.purge/none))))))))

(defn normalize-urban [s]
  (str/replace s #"\[(.+?)\]" (fn [[_ term]]
                                (str "[" term "](https://www.urbandictionary.com/define.php?term=" (str/replace term " " "%20") ")"))))

(defn urban [conn {{{{term :value} "term"} :options} :data
                   :as inter} {translate :translator}]
  (let-flow [res (http/get "https://api.urbandictionary.com/v0/define" {:as :json
                                                                        :query-params {:term term}})]
    (respond conn inter (:channel-message-with-source interaction-response-types)
      :data (if-let [term (first (:list (:body res)))]
              {:embeds [{;; The urban dictionary term does not always match exactly with the user's input.
                         :title (:word term)
                         :description (normalize-urban (:definition term))
                         :url (:permalink term)
                         :fields [{:name (translate :example)
                                   :value (normalize-urban (:example term))}]}]}
              (error (translate :not-found))))))

;;; Command details for exportation

(def commands
  "The global application commands for Assistant."
  [{:fn animanga
    :name "animanga"
    :description "Searches for anime and manga."
    :options [{:type (:integer command-option-types)
               :name "query"
               :description "The anime/manga to search for."
               :required? true
               :autocomplete animanga-autocomplete}]}
   {:fn avatar
    :name "avatar"
    :description "Displays a user's avatar."
    :options [{:type (:user command-option-types)
               :name "user"
               :description "The user to retrieve the avatar of, defaulting to the user who ran the command."
               :required? true}
              {:type (:integer command-option-types)
               :name "size"
               :description "The largest size to return the avatar in. May be lower if size is not available."
               :choices (map #(zipmap [:name :value] (repeat %)) image-sizes)}]}
   {:fn purge
    :name "purge"
    :description "Deletes messages from the current text channel."
    :options [{:type (:integer command-option-types)
               :name "amount"
               :description "The number of messages to delete."
               :required? true
               :min 2
               :max 100}]}
   {:fn urban
    :name "urban"
    :description "Searches the Urban Dictionary."
    :options [{:type (:string command-option-types)
               :name "term"
               :description "The word/phrase to search for."
               :required? true}]}])

(def guild-commands
  "The application commands for individual guilds."
  {"939382862401110058" [{:fn urban
                          :name "urban"
                          :description "Searches the Urban Dictionary."
                          :options [{:type (:string command-option-types)
                                     :name "term"
                                     :description "The word/phrase to search for."
                                     :required? true}]}]})

(def discord-commands (strife/transform commands))
(def discord-guild-commands (update-vals guild-commands strife/transform))

(comment
  #_{:clj-kondo/ignore [:unresolved-namespace]}

  ;; Sets the translator. Useful for inline evaluation in editors.
  (def translate (partial assistant.i18n/translate :en-US)))
