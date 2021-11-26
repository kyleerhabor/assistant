(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'com.kyleerhabor/assistant)
(def version (str "1.0." (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))

(defn clean [_]
  (b/delete {:path "target"}))

(defn copy [_]
  (b/copy-dir {:target-dir class-dir
               :src-dirs ["src" "resources"]}))

(defn jar [_]
  (b/write-pom {:basis basis
                :class-dir class-dir
                :lib lib
                :version version
                :src-dirs ["src"]})
  (copy nil)
  (b/jar {:class-dir class-dir
          :jar-file (str "target/" (name lib) "-" version ".jar")}))

(defn uber [_]
  (clean nil)
  (copy nil)
  (b/compile-clj {:basis basis
                  :class-dir class-dir
                  :src-dirs ["src"]})
  (b/uber {:uber-file (str "target/" (name lib) "-" version "-standalone.jar")
           :class-dir class-dir
           :basis basis
           :main 'assistant/core}))
