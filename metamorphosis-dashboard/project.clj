(defproject test "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [compojure "1.1.5"]
                 [environ "0.3.0"]
                 [org.ocpsoft.prettytime/prettytime "2.1.2.Final"]
                 [org.clojure/tools.logging "0.2.3"]
                 [ring/ring-jetty-adapter "1.1.6"]
                 [ring.velocity "0.1.2"]
                 [com.taobao.metamorphosis/metamorphosis-server "1.4.5-SNAPSHOT"]]
  :resource-paths ["src/main/resources"]
  :source-paths ["src" "src/main/clojure"]
  :main com.github.killme2008.metamorphosis.dashboard.Server
  :plugins [[lein-ring "0.8.2"]]
  :ring {:handler test.handler/app}
  :profiles
  {:dev {:dependencies [[ring-mock "0.1.3"]]
         :resource-paths ["dev"]}})
