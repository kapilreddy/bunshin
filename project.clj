(defproject bunshin "0.1.0-SNAPSHOT"
  :description "Bunshin is a redis based multi instance cache system that aims for high availability."
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [com.taoensso/carmine "2.9.2"]
                 [ketamine "1.0.0"]
                 [clj-time "0.9.0"]]
  :profiles {:dev {:dependencies [[criterium "0.4.3"]
                                  [org.clojure/test.check "0.7.0"]]
                   :plugins [[codox "0.8.11"]]}})
