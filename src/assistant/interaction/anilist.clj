(ns assistant.interaction.anilist
  (:require [assistant.interaction.util :refer [max-autocomplete-choices]]))

(defn media-title [title]
  ;; We could use `some`, but it would break when `title` is nil.
  ((some-fn :english :romaji) title))

;;; GraphQL

(defn media-preview [query type]
  [:Page {:perPage max-autocomplete-choices}
   [[:media {:search query
             :type type}
     [:id
      [:title
       [:english :romaji]]]]]])