(defproject mok "0.1.125-SNAPSHOT"
  :description "Management of chipsea-api"
  :url "http://service.tookok.com"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [compojure "1.5.1"]
                 [selmer "1.0.7" :exclusions [cheshire]]
                 [cheshire "5.6.3"]
                 [http-kit "2.2.0"]
                 [hickory "0.6.0"]
                 [com.postspectacular/rotor "0.1.0"]
                 [lib-noir "0.9.9" :exclusions [[ring/ring-defaults]]]
                 [clj-time "0.12.0"]
                 [com.taoensso/carmine "2.14.0" :exclusions [org.clojure/data.json
                                                             com.taoensso/encore]]
                 [org.clojure/java.jdbc "0.6.1"]
                 [mysql/mysql-connector-java "5.1.34"]
                 [com.zaxxer/HikariCP "2.4.7"]
                 [org.clojure/data.json "0.2.6"]
                 [org.clojars.august/sparrows "0.2.7" :exclusions [[org.clojure/tools.reader] [clj-http]]]
                 [org.clojure/tools.reader "1.0.0-beta3"]
                 [org.clojars.august/session-store "0.1.0" :exclusions [[com.taoensso/carmine]]]
                 [cassc/clj-props "0.1.2"]
                 [clj-http "3.1.0"]
                 [com.taoensso/nippy "2.12.1"]
                 [com.taoensso/timbre "4.7.4" :exclusions [com.taoensso/encore]]
                 [com.taoensso/encore "2.68.0"]
                 [ring-server "0.4.0"]
                 [ring/ring-json "0.4.0"] ;; handling json-body request
                 [ring/ring-defaults "0.2.1"] ;; supports auto set utf8 encoding in content-type
                 [org.slf4j/slf4j-jdk14 "1.7.21"]
                 [dk.ative/docjure "1.10.0"]
                 [commons-validator/commons-validator "1.5.1" :exclusions [commons-logging/commons-logging]]
                 [de.bertschneider/clj-geoip "0.2"]
                 [org.clojure/core.memoize "0.5.9"]
                 [me.raynes/conch "0.8.0"]
                 [com.climate/claypoole "1.1.3"]
                 [clojurewerkz/quartzite "2.0.0"]
                 [net.glxn.qrgen/javase "2.0"];; qrcode
                 
                 [environ "1.1.0"]
                 [alandipert/storage-atom "2.0.1" ]
                 [org.clojure/clojurescript "1.9.227"]
                 [markdown-clj "0.9.89"]
                 [figwheel "0.5.10"]
                 [reagent "0.6.0"]
                 [reagent-forms "0.5.28"]
                 [cljs-pikaday "0.1.4"]
                 [reagent-utils "0.2.1"]
                 [secretary "1.2.3"]
                 [org.clojure/core.async "0.2.391"]
                 ;;[com.cognitect/transit-cljs "0.8.239"]
                 [cljs-ajax "0.5.8"]
                 [flake "0.4.3"]
                 [com.andrewmcveigh/cljs-time "0.5.0"]
                 [clojurewerkz/elastisch "2.2.2"]
                 [thrift-clj "0.3.1"]
                 [ch.qos.logback/logback-classic "1.0.13"] ;; required by thrift-clj
                 
                 [zololabs/jericho-html-parser "3.3.0"]]
  :plugins [[lein-ring "0.11.0" :exclusions [org.clojure/clojure]]
            [lein-cljsbuild "1.1.5" :exclusions [org.clojure/clojure]]
            [lein-environ "1.1.0"]
            [lein-figwheel "0.5.10"]]
  :java-source-paths ["thrift/gen-java"]
  :cljsbuild {:builds {:app {:figwheel true
                             :source-paths ["src-cljs"]
                             :compiler {:output-to "resources/public/cljs/mok.js"
                                        :output-dir "resources/public/cljs/out"
                                        :optimizations :none
                                        :cache-analysis true
                                        :source-map-timestamp true
                                        :source-map true}}}}
  :clean-targets ^{:protect false} [:target-path "target" "resources/public/cljs" "out" "resources/public/prod"]
  ;;:uberjar-name "mok-standalone.jar"
  ;; optimization can be :none, :whitespace, :simple, or :advanced
  :profiles {:uberjar {:omit-source true
                       :env {:production true}
                       :aot [mok.core]
                       :hooks [leiningen.cljsbuild]
                       :cljsbuild
                       {:builds {:app
                                 {:figwheel false
                                  :source-paths ["src-cljs"]
                                  :compiler {:output-to "resources/public/cljs/mok.js"
                                             :output-dir "resources/public/cljs/prod"
                                             :optimizations :advanced ;;:whitespace :advanced
                                             :source-map "resources/public/cljs/mok.map"
                                             :pretty-print false
                                             :externs ^:replace ["externs/jquery-1.9.js" "externs/spectrum.js"]}}}}} ;; "resources/public/js/spectrum/spectrum.js"
             :dev {:env {:dev true}
                   :dependencies [[com.cemerick/piggieback "0.2.1"]
                                  [figwheel-sidecar "0.5.10"]]
                   :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
                   :source-paths ["src-cljs" "dev" "src-dev"]}}
  ;; :repositories {"dev-ichpsea" "http://192.168.0.90/nexus/content/groups/public/"}
  :main mok.core
  :javac-options ["-target" "1.8" "-source" "1.8"] ;;  "-Xlint:-options"
  :aliases {"dev-run" ["run" "dev"]}
  :global-vars {*warn-on-reflection* false}
  :figwheel {:css-dirs ["resources/public/p/css"]
             :open-file-command "emacsclient"}
  ;; :ring {:handler mok.core/app
  ;;        :init    mok.core/init
  ;;        :destroy mok.core/destroy}
  )
