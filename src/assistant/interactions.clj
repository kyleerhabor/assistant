(ns assistant.interactions
  (:require
    [clojure.core.async :refer [<! >! chan go]]
    [clojure.set :refer [rename-keys]]
    [clojure.string :as str]
    [assistant.interaction.anilist :as anilist]
    [assistant.interaction.util :refer [image-sizes max-embed-description-length]]
    [assistant.utils :refer [truncate split-keys]]
    [cheshire.core :as che]
    [clj-http.client :as http]
    [discljord.cdn :as ds.cdn]
    [discljord.formatting :as ds.fmt]
    [discljord.messaging :refer [create-interaction-response!]]
    [discljord.messaging.specs :refer [command-option-types interaction-response-types]]
    [graphql-query.core :refer [graphql-query]]
    [tick.core :as tick]))

(defn avatar-url [user size]
  (ds.cdn/resize (ds.cdn/effective-user-avatar user) size))

(defn respond
  [conn interaction & args]
  (apply create-interaction-response! conn (:id interaction) (:token interaction) args))

;;; Commands

(defn request-async [options]
  (let [result (chan)
        options (assoc options :async? true)]
    (http/request options
      #(go (>! result %))
      ;; In the event of an error, we'll just do nothing. `result` will park on take and, most likely, timeout the
      ;; interaction.
      (constantly nil))
    result))

(defn query-anilist [graphql]
  (go (:data (:body (<! (request-async {:url "https://graphql.anilist.co/"
                                        :method :post
                                        :as :json
                                        :body (che/generate-string {:query graphql})
                                        :content-type :json}))))))

;; Intentionally excluding Japan since that'll be 99% of the anime.
(def anilist-country-codes {"CN" "🇨🇳"
                            "KR" "🇰🇷"
                            "TW" "🇹🇼"})

(defn format-fuzzy-date [day month year]
  (if year
    (if month
      (let [month (str/capitalize (tick/month month))]
        (str month (if day
                     (str " " day)) ", " year))
      ;; Just to be consistent with strings.
      (str year))))

(defn anilist-fuzzy-date [date]
  (format-fuzzy-date (:day date) (:month date) (:year date)))

(def anilist-media-formats {"TV" "TV"
                            "TV_SHORT" "TV Short"
                            "MOVIE" "Movie"
                            "SPECIAL" "Special"
                            "OVA" "OVA"
                            "ONA" "ONA"
                            "MUSIC" "Music"
                            "MANGA" "Manga"
                            "NOVEL" "Novel"
                            "ONE_SHOT" "One Shot"})

(def anilist-media-sources {"ORIGINAL" "Original"
                            "MANGA" "Manga"
                            "LIGHT_NOVEL" "Light Novel"
                            "VISUAL_NOVEL" "Visual Novel"
                            "VIDEO_GAME" "Video Game"
                            "OTHER" "Other"
                            "NOVEL" "Novel"
                            "DOUJINSHI" "Doujinshi"
                            "ANIME" "Anime"
                            "WEB_NOVEL" "Web Novel"
                            "LIVE_ACTION" "Live Action"
                            "GAME" "Game"
                            "COMIC" "Comic"
                            ;; Would've been better for them to actually state the media (probably didn't for breaking
                            ;; changes).
                            "MULTIMEDIA_PROJECT" "Multimedia Project"
                            "PICTURE_BOOK" "Picture Book"})

(def anilist-media-statuses {"FINISHED" "Finished"
                             "RELEASING" "Releasing"
                             "NOT_YET_RELEASED" "Not Yet Released"
                             "CANCELLED" "Canceled"
                             "HIATUS" "Hiatus"})

(def anilist-graphql-fuzzy-date [:day :month :year])

(defn format-duration [n unit]
  (str n " " unit (if (not= 1 n)
                    \s)))

(defn anime
  [conn {{{{id :value} :query} :options} :data
         :as interaction} _]
  (go
    (let [data (<! (query-anilist (graphql-query {:queries [[:Media {:id id}
                                                             [:averageScore :countryOfOrigin
                                                              :description :duration :episodes :format :popularity
                                                              :siteUrl :source :status
                                                              [:startDate anilist-graphql-fuzzy-date]
                                                              [:endDate anilist-graphql-fuzzy-date]
                                                              [:coverImage
                                                               [:color :extraLarge]]
                                                              [:title
                                                               [:english :romaji]]
                                                              [:rankings
                                                               [:allTime :rank :type]]
                                                              [:externalLinks
                                                               [:site :url]]]]]})))
          media (:Media data)
          cover-image (:coverImage media)
          ;; Scores and popularity
          score (:averageScore media)
          popularity (:popularity media)
          rank (fn [type]
                 (first (filter #(and
                                   (:allTime %)
                                   (= type (:type %))) (:rankings media))))
          scoreRank (rank "RATED")
          popularityRank (rank "POPULAR")
          ;; Other stuff
          episodes (:episodes media)
          source (:source media)
          startDate (anilist-fuzzy-date (:startDate media))
          endDate (anilist-fuzzy-date (:endDate media))]
      (respond conn interaction (:channel-message-with-source interaction-response-types)
        :data {:embeds [{:title (str (anilist/media-title (:title media))
                                  (if-let [flag (get anilist-country-codes (:countryOfOrigin media))]
                                    (str " " flag)))
                         :description (-> (:description media)
                                        (str/replace #"</?b>" "**")
                                        (str/replace #"<br>" "\n")
                                        (str/replace #"</?i>" "*")
                                        (str/replace #"[\r\n]+" "\n\n")
                                        (truncate max-embed-description-length))
                         :url (:siteUrl media)
                         :thumbnail {:url (:extraLarge cover-image)}
                         :color (if-let [color (:color cover-image)]
                                  (Long/parseLong (subs color 1) 16))
                         :fields (cond-> [{:name "Format"
                                           :value (get anilist-media-formats (:format media))
                                           :inline true}
                                          {:name "Status"
                                           :value (get anilist-media-statuses (:status media))
                                           :inline true}]
                                   episodes (conj {:name "Episodes"
                                                   ;; <episode count> (<duration in hours> hours and <duration in
                                                   ;; minutes> minutes long/each)
                                                   :value (str episodes
                                                            (if-let [duration (:duration media)]
                                                              (str " ("
                                                                (if (<= 60 duration)
                                                                  (let [minutes (mod duration 60)]
                                                                    (str (format-duration (long (/ duration 60)) "hour")
                                                                      (if (< 0 minutes)
                                                                        (str " and " (format-duration minutes "minute")))))
                                                                  (format-duration duration "minute"))
                                                                " " (if (= 1 episodes)
                                                                      "long"
                                                                      "each") ")")))
                                                   :inline true})
                                   (or score scoreRank) (conj {:name "Score"
                                                               ;; <score>% (**#<rank>**)
                                                               :value (let [score (if score
                                                                                    (str score "%"))
                                                                            rank (if scoreRank
                                                                                   (ds.fmt/bold (str "#" (:rank scoreRank))))]
                                                                        (if (and score rank)
                                                                          (str score " (" rank ")")
                                                                          (or score rank)))
                                                               :inline true})
                                   (or popularity popularityRank) (conj {:name "Popularity"
                                                                         :value (let [score (if popularity
                                                                                              (str (long (/ popularity 1000)) "k"))
                                                                                      rank (if popularityRank
                                                                                             (ds.fmt/bold (str "#" (:rank popularityRank))))]
                                                                                  (if (and score rank)
                                                                                    (str score " (" rank ")")
                                                                                    (or score rank)))
                                                                         :inline true})
                                   source (conj {:name "Source"
                                                 :value (get anilist-media-sources source)
                                                 :inline true})
                                   startDate (conj {:name "Start Date"
                                                    :value startDate
                                                    :inline true})
                                   endDate (conj {:name "End Date"
                                                  :value endDate
                                                  :inline true})
                                   true (conj {:name "Links"
                                               ;; Could also write (comp (partial apply ds.fmt/embed-link) (juxt :site :url))
                                               :value (str/join ", " (map #(ds.fmt/embed-link (:site %) (:url %))
                                                                       (:externalLinks media)))}))}]}))))

(defn anilist-media-autocomplete [conn {{{{query :value} :query} :options} :data
                                        :as interaction} {:keys [type]}]
  (go
    (let [body (<! (query-anilist (graphql-query {:queries [(anilist/media-preview query type)]})))]
      (respond conn interaction (:application-command-autocomplete-result interaction-response-types)
        :data {:choices (for [media (:media (:Page body))]
                          {:name (anilist/media-title (:title media))
                           :value (:id media)})}))))

(defn anime-autocomplete [conn interaction options]
  (anilist-media-autocomplete conn interaction (assoc options :type :ANIME)))

(defn manga-autocomplete [conn interaction options]
  (anilist-media-autocomplete conn interaction (assoc options :type :MANGA)))

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

(defn manga [conn interaction _]
  )

;;; Command exportation (transformation) facilities.

;; The interaction commands. The key is the name and the value contains properties useful for dispatchers (e.g. `:fn`).
;; :options is an array of maps instead of a map of maps since the order matters to Discord.
(def global-commands {:anime {:fn anime
                              :description "Search for an anime."
                              :options [{:type (:integer command-option-types)
                                         :name "query"
                                         :description "The anime to search for."
                                         :required true
                                         :autocomplete anime-autocomplete}]}
                      :avatar {:fn avatar
                               :description "Displays a user's avatar."
                               :options [{:type (:user command-option-types)
                                          :name "user"
                                          :description "The user to retrieve the avatar of. Defaults to the user who ran the command."}
                                         {:type (:integer command-option-types)
                                          :name "size"
                                          :description "The maximum size of the avatar. May be lower if size is not available."
                                          :choices (map #(zipmap [:name :value] (repeat %)) image-sizes)}]}})

(def guild-commands {:manga {:fn manga
                             :description "Search for a manga."
                             :options [{:type (:integer command-option-types)
                                        :name "query"
                                        :description "The manga to search for."
                                        :required true
                                        :autocomplete manga-autocomplete}]}})

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
                              ;; If there are subcommands, there are no options to begin with. Therefore, it's safe to
                              ;; `assoc` here.
                              (assoc command :options (transform-subs subs))
                              command)))) [] commands))


(def discord-global-commands (transform global-commands))
(def discord-guild-commands (transform guild-commands))
