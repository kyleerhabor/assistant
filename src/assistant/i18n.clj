(ns assistant.i18n
  (:require
    [clojure.string :as str]
    [assistant.util :refer [precision]]
    [discljord.formatting :as ds.fmt]
    [tongue.core :as tongue]))

(def strings-en {:months-long ["January" "February" "March" "April" "May" "June" "July" "August" "September" "October"
                               "November" "December"]})

(declare translate)

(def translations
  "A collection of translations for Assistant."
  {:en {;; Constants
        :anime "Anime"
        :cancelled "Canceled"
        :chapters "Chapters"
        :comic "Comic"
        :doujinshi "Doujinshi"
        :end-date "End Date"
        :episodes "Episodes"
        :example "Example"
        :finished "Finished"
        :format "Format"
        :game "Game"
        :hiatus "Hiatus"
        :light-novel "Light Novel"
        :links "Links"
        :live-action "Live Action"
        :manga "Manga"
        :movie "Movie"
        :multimedia-project "Multimedia Project"
        :music "Music"
        :not-yet-released "Not Yet Released"
        :novel "Novel"
        :ona "ONA"
        :one-shot "One Shot"
        :original "Original"
        :other "Other"
        :ova "OVA"
        :picture-book "Picture Book"
        :popularity "Popularity"
        :releasing "Releasing"
        :score "Score"
        :source "Source"
        :special "Special"
        :start-date "Start Date"
        :status "Status"
        :tv "TV"
        :tv-short "TV Short"
        :visual-novel "Visual Novel"
        :volumes "Volumes"
        :web-novel "Web Novel"
        
        ;; Constant phrases
        :guild-only "This command can only be run in a server."
        :not-found "Not found."
        :nsfw-anime "This anime is NSFW and can only be viewed in an NSFW channel."
        :nsfw-manga "This manga is NSFW and can only be viewed in an NSFW channel."
        :missing-manage-messages "Missing Manage Messages permission."

        ;; Functions
        :abbreviate (fn [n]
                      (if (< -1000 n 1000)
                        (str n)
                        (str (let [n (precision (/ n 1000) 1)
                                   ln (long n)]
                               (if (== n ln)
                                 ln
                                 n)) \k)))
        :coll (fn [coll]
                (let [total (count coll)]
                  (case total
                    0 ""
                    1 (str (first coll))
                    2 (str (first coll) " and " (second coll))
                    (str/join ", " (update coll (dec total) (partial str "and "))))))
        :duration (fn [secs]
                    ;; This sucks, but it works.
                    (->> [{:name "hour"
                           :value (/ secs 3600)}
                          {:name "minute"
                           :value (/ (mod secs 3600) 60)}
                          {:name "second"
                           :value (mod secs 60)}]
                      (filter #(<= 1 (:value %)))
                      (mapv #(let [n (long (:value %))]
                               (str n " " (:name %) (if-not (= 1 n) \s))))
                      (translate :en :coll)))
        :fuzzy-date (fn [{:keys [day month year]}]
                      (if year
                        (if month
                          (let [month-long (get (:months-long strings-en) (dec month))]
                            (if day
                              (str month-long " " day ", " year)
                              (str month-long " " year)))
                          (str year))
                        ""))
        
        ;; Nesting
        :anilist {:media {:rank (ds.fmt/bold "#{1}")
                          :popularity (fn [pop rank]
                                        (if pop
                                          (str (translate :en :abbreviate pop)
                                            (if rank
                                              (str " (" (translate :en :anilist.media/rank rank) ")")))
                                          ""))
                          :score (fn [score rank]
                                   (if score
                                     (str score \%
                                       (if rank
                                         (str " (" (ds.fmt/bold (str \# rank)) ")")))
                                     ""))}}
        :interaction {:animanga {:chapters (fn [n vols]
                                             (str n
                                               (if vols
                                                 (str " (" vols " volume" (if-not (= 1 vols) \s) ")"))))
                                 :episodes (fn [n dur]
                                             (str n
                                               (if dur
                                                 (str " (" (translate :en :duration (* dur 60)) " "
                                                   (if (= 1 n)
                                                     "long"
                                                     "each") ")"))))}
                      :purge {:fail "Purge failed."
                              ;; "to purge"?
                              :none "No messages."
                              :success "Purge successful."}}
        :command {:chat-input {:relation {:add {:success "Relation added."}
                                          }}}
        :tongue/missing-key nil}
   :tongue/fallback :en})

(def translate (tongue/build-translate translations))

;;; Guild-specific commands will be represented as their own translations.
