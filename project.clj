(defproject cljam "0.1.0-SNAPSHOT"
  :description "A DNA Sequence Alignment/Map (SAM) library for Clojure"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :repositories [["clojars classic" "http://clojars.org/repo/"]]
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/clojure-contrib "1.2.0"]
                 [org.utgenome.thirdparty/picard "1.86p"]
                 [clj-sub-command "0.1.0-SNAPSHOT"]]
  :profiles {:dev {:dependencies [[midje "1.5.1"]]}}
  :main cljam.core)
