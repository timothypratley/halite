;; Copyright (c) 2022 Viasat, Inc.
;; Licensed under the MIT license

(ns jibe.halite.transpile.to-choco-clj2
  "Another attempt at transpiling halite to choco-clj."
  (:require [clojure.set :as set]
            [weavejester.dependency :as dep]
            [jibe.halite.envs :as halite-envs]
            [jibe.halite.types :as halite-types]
            [jibe.halite.transpile.lowering :as lowering]
            [jibe.halite.transpile.ssa :as ssa
             :refer [DerivationName Derivations SpecInfo SpecCtx]]
            [jibe.halite.transpile.util :refer [mk-junct]]
            [schema.core :as s]
            [viasat.choco-clj :as choco-clj]))

(declare Bound)

(s/defschema SpecBound
  {:$type halite-types/NamespacedKeyword
   halite-types/BareKeyword (s/recursive #'Bound)})

(s/defschema AtomBound
  (s/cond-pre
   s/Int
   s/Bool
   {:$in (s/cond-pre
          #{(s/cond-pre s/Int s/Bool)}
          [(s/one s/Int :lower) (s/one s/Int :upper)])}))

(s/defschema Bound
  (s/conditional
   :$type SpecBound
   :else AtomBound))

;;;;;;;;; Bound Spec-ification ;;;;;;;;;;;;;;;;

(s/defschema ^:private FlattenedVar
  [(s/one halite-types/BareKeyword :var-kw)
   (s/one (s/enum :Boolean :Integer) :type)])

(s/defschema ^:private FlattenedVars
  {halite-types/BareKeyword (s/cond-pre FlattenedVar (s/recursive #'FlattenedVars))})

(s/defn ^:private flatten-vars :- FlattenedVars
  ([sctx :- SpecCtx, spec-bound :- SpecBound]
   (flatten-vars sctx [] "" spec-bound))
  ([sctx :- SpecCtx, parent-spec-ids :- [halite-types/NamespacedKeyword], prefix :- s/Str, spec-bound :- SpecBound]
   (reduce
    (fn [vars [var-kw htype]]
      (cond
        (#{:Integer :Boolean} htype)
        (assoc vars var-kw
               [(keyword (str prefix (name var-kw)))
                (if-let [val-bound (some-> spec-bound var-kw :$in)]
                  (if (and (set? val-bound) (= :Integer htype) (< 1 (count val-bound)))
                    (throw (ex-info "TODO: enumerated bounds" {:val-bound val-bound :var-kw var-kw})) #_val-bound
                    htype)
                  htype)])

        (halite-types/spec-type? htype)
        (cond-> vars
          (or (contains? spec-bound var-kw)
              (every? #(not= % htype) parent-spec-ids))
          (assoc var-kw
                 (flatten-vars sctx (conj parent-spec-ids (:$type spec-bound)) (str prefix (name var-kw) "|")
                               (get spec-bound var-kw {:$type htype}))))

        :else (throw (ex-info (format "BUG! Variables of type '%' not supported yet" htype)
                              {:var-kw var-kw :type htype}))))
    {}
    (-> spec-bound :$type sctx :spec-vars))))

(defn- leaves [m]
  (if (map? m)
    (mapcat leaves (vals m))
    [m]))

(s/defn ^:private flatten-get-chain :- s/Symbol
  [rename-scope :- FlattenedVars var-kw-stack :- [s/Keyword] expr]
  (cond
    (seq? expr)
    (let [[_get subexpr var-kw] expr]
      (recur rename-scope (cons var-kw var-kw-stack) subexpr))

    (symbol? expr)
    (let [var-kw-stack (vec (cons (keyword expr) var-kw-stack))
          new-var-kw (get-in rename-scope var-kw-stack)]
      (if (nil? new-var-kw)
        (throw (ex-info "Skip constraint, specs didn't unfold sufficiently"
                        {:skip-constraint? true :form expr :rename-scope rename-scope :var-kw-stack var-kw-stack}))
        (symbol (first new-var-kw))))

    :else
    (throw (ex-info "BUG! Not a get chain!"
                    {:form expr :rename-scope rename-scope :var-kw-stack var-kw-stack}))))

(s/defn ^:private flatten-expression
  [rename-scope :- FlattenedVars expr]
  (cond
    (or (integer? expr) (boolean? expr)) expr
    (symbol? expr) (if-let [[var-kw htype] (rename-scope (keyword expr))]
                     (if (#{:Integer :Boolean} htype)
                       (symbol var-kw)
                       (throw (ex-info "BUG! Found 'naked' instance-valued variable reference"
                                       {:expr expr :rename-scope rename-scope})))
                     expr)
    (seq? expr) (let [[op & args] expr]
                  (condp = op
                    'get* (flatten-get-chain rename-scope [] expr)
                    'let (let [[_let bindings body] expr
                               [rename-scope bindings] (reduce
                                                        (fn [[rename-scope bindings] [var-sym expr]]
                                                          [(dissoc rename-scope (keyword var-sym))
                                                           (conj bindings var-sym (flatten-expression rename-scope expr))])
                                                        [rename-scope []]
                                                        (partition 2 bindings))]
                           (list 'let bindings (flatten-expression rename-scope body)))
                    (->> args (map (partial flatten-expression rename-scope)) (apply list op))))
    :else (throw (ex-info "BUG! Cannot flatten expression" {:form expr :rename-scope rename-scope}))))

(s/defn ^:private flatten-spec-constraints
  [sctx :- SpecCtx, tenv :- (s/protocol halite-envs/TypeEnv), vars :- FlattenedVars, spec-bound :- SpecBound, spec-info :- SpecInfo]
  (let [senv (ssa/as-spec-env sctx)
        spec-to-inline (-> spec-bound :$type sctx)
        old-scope (->> spec-to-inline :spec-vars keys (map symbol) set)]
    (reduce
     (fn [{:keys [derivations] :as spec-info} [cname id]]
       (let [expr (ssa/form-from-ssa old-scope (ssa/prune-dgraph (:derivations spec-to-inline) #{id} true) id)
             fexpr (try (flatten-expression vars expr)
                        (catch clojure.lang.ExceptionInfo ex
                          (if (:skip-constraint? (ex-data ex))
                            nil
                            (throw ex))))]
         (if fexpr
           (let [[dgraph id] (ssa/form-to-ssa {:senv senv :tenv tenv :env {} :dgraph derivations} fexpr)]
             (-> spec-info
                 (assoc :derivations dgraph)
                 (update :constraints conj [cname id])))
           spec-info)))
     spec-info
     (:constraints spec-to-inline))))

(s/defn ^:private flatten-spec-bound :- SpecInfo
  [sctx :- SpecCtx, tenv :- (s/protocol halite-envs/TypeEnv), vars :- FlattenedVars, spec-bound :- SpecBound, spec-info :- SpecInfo]
  (let [senv (ssa/as-spec-env sctx)
        spec-vars (-> spec-bound :$type sctx :spec-vars)
        add-equality-constraint
        ,,(fn [{:keys [derivations] :as spec-info} var-kw v]
            (let [var-sym (->> var-kw vars first symbol)
                  [dgraph constraint] (ssa/constraint-to-ssa senv tenv derivations ["$=" (list '= var-sym v)])]
              (-> spec-info
                  (assoc :derivations dgraph)
                  (update :constraints conj constraint))))]
    (reduce
     (fn [{:keys [derivations] :as spec-info} [var-kw v]]
       (cond
         (= v ::skip) spec-info
         
         (or (integer? v) (boolean? v))
         (add-equality-constraint spec-info var-kw v)

         (map? v)
         (condp #(contains? %2 %1) v
           :$type (flatten-spec-bound sctx tenv (vars var-kw) v spec-info)
           :$in (let [val-bound (:$in v)]
                  (cond
                    (set? val-bound)
                    (cond-> spec-info
                      (= 1 (count val-bound)) (add-equality-constraint var-kw (first val-bound)))

                    (vector? val-bound) 
                    (let [[lower upper] val-bound
                          var-sym (->> var-kw vars first symbol)
                          [dgraph constraint] (ssa/constraint-to-ssa
                                               senv tenv derivations
                                               ["$range" (list 'and
                                                               (list '<= lower var-sym)
                                                               (list '<= var-sym upper))])]
                      (-> spec-info
                          (assoc :derivations dgraph)
                          (update :constraints conj constraint)))
                    :else (throw (ex-info "Not a valid bound" {:bound v}))))
           :else (throw (ex-info "Not a valid bound" {:bound v})))

         :else (throw (ex-info "BUG! Unrecognized spec-bound type" {:spec-bound spec-bound :var-kw var-kw :v v}))))
     (flatten-spec-constraints sctx tenv vars spec-bound spec-info)
     (map (fn [[var-kw v]]
            [var-kw
             (if (map? v)
               (get spec-bound var-kw {:$type (get spec-vars var-kw)})
               (get spec-bound var-kw ::skip))])
          vars))))

(s/defn ^:private flatten-constraints :- SpecInfo
  [sctx :- SpecCtx, tenv :- (s/protocol halite-envs/TypeEnv), vars :- FlattenedVars, spec-bound :- SpecBound, spec-info :- SpecInfo]
  (flatten-spec-bound sctx tenv vars spec-bound spec-info))

(s/defn ^:private spec-ify-bound :- SpecInfo
  [sctx :- SpecCtx, spec-bound :- SpecBound]
  (let [{:keys [spec-vars constraints refines-to derivations] :as spec-info} (-> spec-bound :$type sctx)]
    (when (seq refines-to)
      (throw (ex-info (format "BUG! Cannot spec-ify refinements") {:spec-info spec-info})))
    (let [senv (ssa/as-spec-env sctx)
          flattened-vars (flatten-vars sctx spec-bound)
          new-spec {:spec-vars (->> flattened-vars leaves (into {}))
                    :constraints []
                    :refines-to {}
                    :derivations {}}
          tenv (halite-envs/type-env-from-spec (ssa/as-spec-env sctx) (dissoc new-spec :derivations))]
      (flatten-constraints sctx tenv flattened-vars spec-bound new-spec))))

;;;;;;;;;;; Conversion to Choco ;;;;;;;;;

(s/defn ^:private to-choco-type :- choco-clj/ChocoVarType
  [var-type :- halite-types/HaliteType]
  (cond
    (= :Integer var-type) :Int
    (= :Boolean var-type) :Bool
    :else (throw (ex-info (format "BUG! Can't convert '%s' to choco var type" var-type) {:var-type var-type}))))

(s/defn ^:private to-choco-spec :- choco-clj/ChocoSpec
  "Convert an L0 resource spec to a Choco spec"
  [spec-info :- halite-envs/SpecInfo]
  {:vars (-> spec-info :spec-vars (update-keys symbol) (update-vals to-choco-type))
   :constraints (->> spec-info :constraints (map second) set)})

;;;;;;;;;;;;;;; Main API ;;;;;;;;;;;;;;;;;

(s/defschema Opts
  {:default-int-bounds [(s/one s/Int :lower) (s/one s/Int :upper)]})

(def default-options
  (s/validate
   Opts
   {:default-int-bounds [-1000 1000]}))

(s/defn transpile :- choco-clj/ChocoSpec
  "Transpile the spec-bound into a semantically equivalent choco-clj spec."
  ([senv :- (s/protocol halite-envs/SpecEnv), spec-bound :- SpecBound]
   (transpile senv spec-bound default-options))
  ([senv :- (s/protocol halite-envs/SpecEnv), spec-bound :- SpecBound, opts :- Opts]
   (binding [ssa/*next-id* (atom 0)]
     (let [spec-id (:$type spec-bound)
           sctx (->> spec-id (ssa/build-spec-ctx senv) (lowering/lower))]
       (->> spec-bound
            (spec-ify-bound sctx)
            (ssa/spec-from-ssa)
            (to-choco-spec))))))
