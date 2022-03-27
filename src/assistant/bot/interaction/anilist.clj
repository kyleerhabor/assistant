(ns assistant.bot.interaction.anilist
  (:require
    [clojure.string :as str]
    [assistant.bot.interaction.util :refer [max-autocomplete-choices max-embed-description-length]]
    [assistant.util :refer [truncate]]
    [aleph.http :as http]
    [cheshire.core :as che]
    [manifold.deferred :as mfd]))

(def country-codes {"JP" "🇯🇵"
                    "CN" "🇨🇳"
                    "KR" "🇰🇷"
                    ;; -500 social credit
                    "TW" "🇹🇼"})

(defn html-to-md
  "Converts common HTML in AniList to Markdown."
  [html]
  (-> html
    ;; Very basic and susceptible to failure, but "works".
    (str/replace #"[\r\n]" "")
    (str/replace #"<br>" "\n")
    (str/replace #"</?i>" "*")
    (str/replace #"</?b>" "**")))

(defn media-title
  "Returns the title of a media, favoring english over romaji."
  [title]
  ;; We could use `some`, but it would break when `title` is nil.
  ((some-fn :english :romaji) title))

(defn media-rank [rankings type]
  (:rank (first (filter #(and
                           (:allTime %)
                           (= type (:type %))) rankings))))

;;; Formatters

(defn format-media-description [desc]
  (truncate (html-to-md desc) max-embed-description-length))

(defn format-media-title [{country :countryOfOrigin
                           :as media}]
  (str (media-title (:title media))
    (if (and country (not= "JP" country))
      (str " " (get country-codes country)))
    (if (:isAdult media) " 🔞")))

;;; GraphQL

(def fuzzy-date
  [:day :month :year])

(defn media
  [id]
  [:Media {:id id}
   [:averageScore :countryOfOrigin :description :format :isAdult :popularity :siteUrl :type
    ;; Anime only
    :episodes :duration
    ;; Manga only
    :chapters :volumes
    ;; Both
    [:source {:version 3}]
    [:status {:version 2}]
    [:startDate fuzzy-date]
    [:endDate fuzzy-date]
    [:coverImage
     [:color :extraLarge]]
    [:title
     [:english :romaji]]
    [:rankings
     [:allTime :rank :type]]
    [:externalLinks
     [:site :url]]]])

(defn media-preview
  ([query] (media-preview query nil))
  ([query {:keys [adult?]}]
   [:Page {:perPage max-autocomplete-choices}
    [[:media (cond-> {:search query}
               (some? adult?) (assoc :isAdult adult?))
      [:id :format
       [:title
        [:english :romaji]]]]]]))

;;; HTTP

(defn query [graphql]
  (mfd/chain (http/post "https://graphql.anilist.co/" {:as :json
                                                       :body (che/generate-string {:query graphql})
                                                       :content-type :json})
    :body :data))
