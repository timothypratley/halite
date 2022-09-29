;; Copyright (c) 2022 Viasat, Inc.
;; Licensed under the MIT license

(ns jibe.halite.doc.md-spec
  (:require [clojure.string :as string]))

(defn- embed-bnf [{:keys [mode image-dir]} label]
  ["![" label "](" (when (= :user-guide mode) (str image-dir "/")) "halite-bnf-diagrams/spec-syntax/" label ".svg" ")\n\n"])

(defn spec-md [run-config]
  (->> ["A spec-map is a data structure used to define specs that are in context for evaluating some expressions.\n\n"
        "Specs include variables which have types as:\n\n"
        (embed-bnf run-config "type")
        "The variables for a spec are defined in a spec-var-map:\n\n"
        (embed-bnf run-config "spec-var-map")
        "Constraints on those variables are defined as:\n\n"
        (embed-bnf run-config "constraints")
        "Any applicable refinements are defined as:\n\n"
        (embed-bnf run-config "refinement-map")
        "All the specs in scope are packaged up into a spec-map:\n\n"
        (embed-bnf run-config "spec-map")]
       flatten
       (apply str)))
