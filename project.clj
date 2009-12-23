(defproject
  clojurebot "0.1.0-SNAPSHOT"
  :description "An IRC bot written in Clojure"
  :dependencies
    [
     [org.clojure/clojure "1.1.0-master-SNAPSHOT"]
     [org.clojure/clojure-contrib "1.0-SNAPSHOT"]
     [org.clojars.snuxoll/clojureql "1.0.0"]
     [pircbot/pircbot "1.4.2"]
     [org.danlarkin/clojure-json "1.1-SNAPSHOT"]]
  :dev-dependencies [[leiningen/lein-swank "1.0.0-SNAPSHOT"]])