(ns snakeskin.alpha
  (:require [clojure.pprint :use [pprint]])
  (:import [org.snakeyaml.engine.v2.api.lowlevel Compose]
           [org.snakeyaml.engine.v2.api LoadSettings LoadSettingsBuilder]
           [org.snakeyaml.engine.v2.nodes Node]
           [java.io FileInputStream]))

;; TODO Default is 50. 1000 is problematic. cf. https://en.wikipedia.org/wiki/Billion_laughs_attack
(def max-aliases 1000)

(def builder (LoadSettings/builder))
(def compose ^Compose (-> ^LoadSettingsBuilder builder (.setMaxAliasesForCollections max-aliases) .build Compose.))

(def yaml-tag-handlers
  {"tag:yaml.org,2002:set"    #(into #{} (keys %))
   "tag:yaml.org,2002:binary" identity ; TODO This is probably not compliant with https://yaml.org/type/binary.html
   "tag:yaml.org,2002:int"    #(Long/parseLong %)
   "tag:yaml.org,2002:float"  #(Float/parseFloat %)
   "tag:yaml.org,2002:bool"   {"true" true "false" false}
   "tag:yaml.org,2002:null"   (constantly nil)
   "tag:yaml.org,2002:str"    identity
   "tag:yaml.org,2002:seq"    identity
   "tag:yaml.org,2002:map"    identity});

(defrecord Tag [tag value])

;; TODO default handling of unknown tag types but doesn't allow functions for user-defined tags
(defn apply-tag [node value]
  (let [tag (->> ^Node node .getTag .getValue)
        tagfn (get yaml-tag-handlers tag ::unknown-tag)]
    (if (= tagfn ::unknown-tag)
      (Tag. tag value)
      (tagfn value))))

(defprotocol YamlNode
   (convert-node [this]))

(extend-protocol YamlNode
  org.snakeyaml.engine.v2.nodes.SequenceNode
  (convert-node [this] (apply-tag this (into [] (map convert-node) (.getValue this))))

  ;; TODO /Technically/ YAML maps have order. May want to deftype a Clojure map type that does the same.
  org.snakeyaml.engine.v2.nodes.MappingNode
  (convert-node [this] (apply-tag this (into {} (map convert-node) (.getValue this))))

  org.snakeyaml.engine.v2.nodes.NodeTuple
  (convert-node [this] [(convert-node (.getKeyNode this)) (convert-node (.getValueNode this))])

  org.snakeyaml.engine.v2.nodes.ScalarNode
  (convert-node [this] (apply-tag this (.getValue this))))

(defn -main [& args]
  (doseq [f args]
      (prn :file f)
      (->>
        (FileInputStream. ^String f)
        (.composeAllFromInputStream ^Compose compose)
        (map convert-node)
        pprint)))

(comment
  (->>
    "{1: 2, A: [Z, [3,4,null,true,false,2.3], {s: 5, i: 1, B: !!set {8, A, 1.2}}]}\n---\n[1, 2, 3]\n---\nA\n---\n - 1.2\n - XYZ\n - null\n - false\n - &foo bar\n - [zap, *foo]\n---\n!!set\n ? foo\n ? bar\n ? 345"
    (.composeAllFromString compose)
    (map convert-node)
    pprint)
  (->>
    "/path/to/file.yaml"
    (FileInputStream.)
    (.composeAllFromInputStream compose)
    (map convert-node)
    pprint))
          
