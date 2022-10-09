;; Copyright (c) 2022 Viasat, Inc.
;; Licensed under the MIT license

(ns jibe.halite.propagate.prop-choco
  "Handles propagation for single specs that can be directly mapped to viasat.choco-clj-opt.
  Specifically: single specs with (possibly optional) boolean or integer variables,
  and no refinements."
  (:require [schema.core :as s]
            [jibe.halite.halite-envs :as halite-envs]
            [jibe.halite.halite-types :as halite-types]
            [viasat.choco-clj-opt :as choco-clj]))


(s/defschema AtomBound
  (s/cond-pre
   s/Int
   s/Bool
   (s/enum :Unset)
   {:$in (s/cond-pre
          #{(s/cond-pre s/Int s/Bool (s/enum :Unset))}
          [(s/one s/Int :lower) (s/one s/Int :upper) (s/optional (s/enum :Unset) :Unset)])}))

(s/defschema SpecBound
  {halite-types/BareKeyword AtomBound})

(s/defn ^:private to-choco-type :- choco-clj/ChocoVarType
  [var-type :- halite-envs/VarType]
  (cond
    (or (= [:Maybe "Integer"] var-type) (= "Integer" var-type)) :Int
    (or (= [:Maybe "Boolean"] var-type) (= "Boolean" var-type)) :Bool
    :else (throw (ex-info (format "BUG! Can't convert '%s' to choco var type" var-type) {:var-type var-type}))))

(defn- error->unsatisfiable
  [form]
  (cond
    (seq? form) (if (= 'error (first form))
                  (if (not (string? (second form)))
                    (throw (ex-info "BUG! Expressions other than string literals not currently supported as arguments to error"
                                    {:form form}))
                    (list 'unsatisfiable))
                  (apply list (first form) (map error->unsatisfiable (rest form))))
    (map? form) (update-vals form error->unsatisfiable)
    (vector? form) (mapv error->unsatisfiable form)
    :else form))

(s/defn ^:private lower-spec :- choco-clj/ChocoSpec
  [spec :- halite-envs/SpecInfo]
  {:vars (-> spec :spec-vars (update-keys symbol) (update-vals to-choco-type))
   :optionals (->> spec :spec-vars
                   (filter (comp halite-types/maybe-type?
                                 (partial halite-envs/halite-type-from-var-type {})
                                 val))
                   (map (comp symbol key)) set)
   :constraints (->> spec :constraints (map (comp error->unsatisfiable second)) set)})

(s/defn ^:private lower-atom-bound :- choco-clj/VarBound
  [b :- AtomBound]
  (cond-> b (map? b) :$in))

(s/defn ^:private lower-spec-bound :- choco-clj/VarBounds
  [bound :- SpecBound]
  (-> bound (update-vals lower-atom-bound) (update-keys symbol)))

(s/defn ^:private raise-spec-bound :- SpecBound
  [bound :- choco-clj/VarBounds]
  (-> bound (update-keys keyword) (update-vals #(cond->> % (coll? %) (hash-map :$in)))))

(s/defschema Opts
  {:default-int-bounds [(s/one s/Int :lower) (s/one s/Int :upper)]})

(def default-options
  (s/validate
   Opts
   {:default-int-bounds [-1000 1000]}))

(s/defn propagate :- SpecBound
  ([spec :- halite-envs/SpecInfo, initial-bound :- SpecBound]
   (propagate spec default-options initial-bound))
  ([spec :- halite-envs/SpecInfo, opts :- Opts, initial-bound :- SpecBound]
   (binding [choco-clj/*default-int-bounds* (:default-int-bounds opts)]
     (-> spec
         (lower-spec)
         (choco-clj/propagate (lower-spec-bound initial-bound))
         (raise-spec-bound)))))
