package at.jku.risc.uarau;

import at.jku.risc.uarau.data.*;
import at.jku.risc.uarau.util.DataUtil;
import at.jku.risc.uarau.util.Pair;
import at.jku.risc.uarau.util.Triple;
import at.jku.risc.uarau.util.UnmodifiableDeque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public final class Algorithm {
    public static Set<Solution> solve(String problem, String proximityRelations, float lambda) {
        List<Term> sides = Parser.parseProblem(problem);
        return solve(sides.get(0), sides.get(1), Parser.parseProximityRelations(proximityRelations), lambda, Math::min, false, true);
    }
    
    public static Set<Solution> solve(Term lhs, Term rhs, Collection<ProximityRelation> relations, float lambda, TNorm t_norm, boolean linear, boolean witness) {
        return new Algorithm(lhs, rhs, relations, t_norm, lambda, linear, witness).run();
    }
    
    Logger log = LoggerFactory.getLogger(Algorithm.class);
    
    private final ProximityMap R;
    private final Term lhs, rhs;
    private final TNorm t_norm;
    private final float lambda;
    private final boolean linear, witness;
    
    private Algorithm(Term lhs, Term rhs, Collection<ProximityRelation> relations, TNorm t_norm, float lambda, boolean linear, boolean witness) {
        this.rhs = rhs;
        this.lhs = lhs;
        this.R = new ProximityMap(rhs, lhs, relations, lambda);
        this.t_norm = t_norm;
        this.lambda = lambda;
        this.linear = linear;
        this.witness = witness;
    }
    
    private Set<Solution> run() {
        // TODO analyze for correspondence/mapping properties
        Config initCfg = new Config(lhs, rhs);
        log.info("SOLVING  ::  λ={}\n                   ::  {}{}", lambda, initCfg.A.peek(), R.toString("\n                   ::  "));
        
        Deque<Config> branches = new ArrayDeque<>();
        branches.push(initCfg);
        Deque<Config> linearSolutions = new ArrayDeque<>();
        
        // APPLY RULES
        BRANCHING:
        while (!branches.isEmpty()) {
            assert DataUtil.unique(branches);
            Config config = branches.removeFirst();
            while (!config.A.isEmpty()) {
                AUT aut = config.A.removeFirst();
                // TRIVIAL
                if (aut.T1.isEmpty() && aut.T2.isEmpty()) {
                    config.substitutions.addLast(new Substitution(aut.var, Term.ANON));
                    log.debug("TRI => {}", config);
                    continue;
                }
                // DECOMPOSE
                Deque<Config> children = decompose(aut, config);
                if (!children.isEmpty()) {
                    for (Config child : children) {
                        branches.addLast(child);
                    }
                    log.debug("DEC => {}", DataUtil.joinString(children, " ", ""));
                    continue BRANCHING;
                }
                // SOLVE
                config.S.addLast(aut);
                log.debug("SOL => {}", config);
            }
            assert config.A.isEmpty();
            linearSolutions.addLast(config);
        }
        log.debug("Common proximate memory ({}): {}", R.proximatesMemory.size(), R.proximatesMemory);
        
        // POST PROCESS
        assert DataUtil.unique(linearSolutions);
        log.info("Solutions (LINEAR):\n                   ::  {}", DataUtil.joinString(linearSolutions, "\n                   ::  ", "--"));
        if (linear && !witness) {
            return linearSolutions.stream().map(this::toSolution).collect(Collectors.toSet());
        }
        // EXPAND
        Deque<Config> expandedSolutions = new ArrayDeque<>(linearSolutions.size());
        for (Config linearSolution : linearSolutions) {
            Deque<AUT> S_expanded = linearSolution.S.stream()
                    .map(aut -> expand(aut, linearSolution.peekVar()))
                    .collect(Collectors.toCollection(ArrayDeque::new));
            
            expandedSolutions.addLast(linearSolution.copy_update_S(S_expanded));
        }
        
        assert DataUtil.unique(expandedSolutions);
        log.info("Solutions (EXPANDED):\n                   ::  {}", DataUtil.joinString(expandedSolutions, "\n                   ::  ", "--"));
        if (linear) {
            return expandedSolutions.stream().map(this::toSolution).collect(Collectors.toSet());
        }
        
        // MERGE
        Deque<Config> mergedSolutions = new ArrayDeque<>();
        for (Config expandedSolution : expandedSolutions) {
            Deque<AUT> S_expanded = new ArrayDeque<>(expandedSolution.S);
            Deque<AUT> S_merged = new ArrayDeque<>();
            while (!S_expanded.isEmpty()) {
                int freshVar = expandedSolution.peekVar();
                
                AUT merger = S_expanded.removeFirst();
                Deque<Term> R11 = new ArrayDeque<>(merger.T1);
                Deque<Term> R12 = new ArrayDeque<>(merger.T2);
                
                Deque<AUT> unmerged = new ArrayDeque<>();
                Deque<Integer> mergedVars = new ArrayDeque<>();
                mergedVars.addLast(merger.var);
                for (AUT candidate : S_expanded) {
                    Triple<Deque<Term>, Deque<Term>, Integer> merged = merge(R11, candidate.T1, R12, candidate.T2, freshVar);
                    if (merged.a.isEmpty() || merged.b.isEmpty()) {
                        unmerged.addLast(candidate);
                    } else { // APPLY MERGE
                        mergedVars.addLast(candidate.var);
                        R11 = merged.a;
                        R12 = merged.b;
                        freshVar = merged.c;
                    }
                }
                S_expanded = unmerged;
                if (mergedVars.size() == 1) { // nothing merged -> substitution would be redundant
                    S_merged.addLast(merger);
                } else {
                    final Term y = new Term(freshVar);
                    mergedVars.forEach(var -> expandedSolution.substitutions.addLast(new Substitution(var, y)));
                    S_merged.addLast(new AUT(freshVar, R11, R12));
                }
            }
            Config mergedSolution = expandedSolution.copy_update_S(S_merged);
            assert DataUtil.unique(mergedSolution.S);
            mergedSolutions.addLast(mergedSolution);
        }
        log.info("Solutions (MERGED):\n                   ::  {}", DataUtil.joinString(mergedSolutions, "\n                   ::  ", "--"));
        return mergedSolutions.stream().map(this::toSolution).collect(Collectors.toSet());
    }
    
    private Deque<Config> decompose(AUT aut, Config cfg) {
        Deque<Config> children = new ArrayDeque<>();
        for (String h : R.commonProximates(aut.heads())) {
            Pair<List<Deque<Term>>, Float> map1 = map(h, aut.T1, cfg.alpha1);
            List<Deque<Term>> Q1 = map1.a;
            float alpha1 = map1.b;
            
            Pair<List<Deque<Term>>, Float> map2 = map(h, aut.T2, cfg.alpha2);
            List<Deque<Term>> Q2 = map2.a;
            float alpha2 = map2.b;
            
            // CHECK DEC
            if (alpha1 < lambda || alpha2 < lambda) {
                continue;
            }
            assert Q1 != null && Q2 != null;
            if (Q1.stream().anyMatch(q -> !consistent(q)) || Q2.stream().anyMatch(q -> !consistent(q))) {
                continue;
            }
            
            // APPLY DEC
            Config child = cfg.copy();
            Term[] h_args = new Term[R.arity(h)];
            for (int i = 0; i < h_args.length; i++) {
                int y_i = child.freshVar();
                h_args[i] = new Term(y_i);
                child.A.addLast(new AUT(y_i, Q1.get(i), Q2.get(i)));
            }
            Term h_term = R.isMappedVar(h) ? new Term(h) : new Term(h, h_args);
            child.substitutions.addLast(new Substitution(aut.var, h_term));
            child.alpha1 = alpha1;
            child.alpha2 = alpha2;
            
            children.addLast(child);
        }
        return children;
    }
    
    private Pair<List<Deque<Term>>, Float> map(String h, Deque<Term> T, float beta) {
        int h_arity = R.arity(h);
        List<Deque<Term>> Q = new ArrayList<>(h_arity);
        for (int i = 0; i < h_arity; i++) {
            Q.add(new ArrayDeque<>());
        }
        for (Term t : T) {
            assert !t.isVar() && t.arguments != null;
            ProximityRelation proximityRelation = R.proximityRelation(h, t.head);
            List<List<Integer>> h_to_t = proximityRelation.argRelation;
            for (int i = 0; i < h_arity; i++) {
                for (int t_mapped_idx : h_to_t.get(i)) {
                    // Q[i] => set of args which h|i maps to
                    Q.get(i).addLast(t.arguments.get(t_mapped_idx));
                }
            }
            beta = t_norm.apply(beta, proximityRelation.proximity);
            if (beta < lambda) {
                return new Pair<>(null, beta); // should not be dereferenced
            }
        }
        return new Pair<>(Q, beta);
    }
    
    private boolean consistent(Deque<Term> terms) {
        return !specialConjunction(terms, Term.UNUSED_VAR).a.isEmpty();
    }
    
    private AUT expand(AUT aut, int freshVar) {
        Pair<Deque<Term>, Integer> pair1 = specialConjunction(aut.T1, freshVar);
        Deque<Term> C1 = pair1.a;
        freshVar = pair1.b;
        
        Pair<Deque<Term>, Integer> pair2 = specialConjunction(aut.T2, freshVar);
        Deque<Term> C2 = pair2.a;
        
        assert !C1.isEmpty() && !C2.isEmpty();
        return new AUT(aut.var, C1, C2);
    }
    
    private Triple<Deque<Term>, Deque<Term>, Integer> merge(Deque<Term> T11, Deque<Term> T12, Deque<Term> T21, Deque<Term> T22, int freshVar) {
        Pair<Deque<Term>, Integer> pair1 = specialConjunction(DataUtil.conjunction(T11, T12), freshVar);
        Deque<Term> Q1 = pair1.a;
        freshVar = pair1.b;
        
        if (Q1.isEmpty()) { // optimization: merge fails if either side is empty, so we can stop here
            return new Triple<>(Q1, Q1, freshVar);
        }
        
        Pair<Deque<Term>, Integer> pair2 = specialConjunction(DataUtil.conjunction(T21, T22), freshVar);
        Deque<Term> Q2 = pair2.a;
        freshVar = pair2.b;
        
        return new Triple<>(Q1, Q2, freshVar);
    }
    
    private Pair<Deque<Term>, Integer> specialConjunction(Deque<Term> terms, int freshVar) {
        boolean consistencyCheck = freshVar == Term.UNUSED_VAR;
        Deque<State> branches = new ArrayDeque<>();
        terms = terms.stream().filter(t -> !Term.ANON.equals(t)).collect(DataUtil.toDeque());
        branches.push(new State(terms, freshVar));
        log.trace("  {}", branches);
        
        Deque<Term> solutions = consistencyCheck ? new ArrayDeque<>(1) : new ArrayDeque<>();
        BRANCHING:
        while (!branches.isEmpty()) {
            State state = branches.pop();
            while (!state.expressions.isEmpty()) {
                Expression expr = state.expressions.pop();
                // REMOVE
                if (consistencyCheck && expr.T.size() <= 1 || expr.T.isEmpty()) {
                    state.s.addLast(new Substitution(expr.var, Term.ANON));
                    continue;
                }
                // REDUCE
                for (String h : R.commonProximates(expr.T.stream().map(t -> t.head).collect(DataUtil.toDeque()))) {
                    List<Deque<Term>> Q = map(h, expr.T, 1.0f).a;
                    assert Q != null;
                    State childState = state.copy();
                    
                    Term[] h_args = new Term[R.arity(h)];
                    for (int i = 0; i < h_args.length; i++) {
                        int y_i = childState.freshVar();
                        h_args[i] = new Term(y_i);
                        Q.get(i).removeIf(Term.ANON::equals);
                        childState.expressions.push(new Expression(y_i, Q.get(i)));
                    }
                    freshVar = Math.max(freshVar, childState.peekVar());
                    Term h_term = R.isMappedVar(h) ? new Term(h) : new Term(h, h_args);
                    childState.s.addLast(new Substitution(expr.var, h_term));
                    branches.push(childState);
                    if (log.isTraceEnabled()) {
                        log.trace("  RED => {}", childState);
                    }
                }
                continue BRANCHING;
            }
            if (consistencyCheck) {
                log.trace("  => consistent");
                return new Pair<>(new UnmodifiableDeque<>(Term.ANON), freshVar);
            }
            solutions.addLast(Substitution.applyAll(state.s, state.peekVar()));
        }
        if (consistencyCheck) {
            log.trace("  => NOT consistent");
        } else {
            if (solutions.size() < 20) {
                log.debug("terms: {} -> conjunctions: ({}) {}", terms, solutions.size(), solutions);
            } else {
                log.debug("terms: {} -> conjunctions: ({})", terms, solutions.size());
            }
            if (log.isTraceEnabled()) {
                log.trace("  => {}", solutions);
            }
        }
        return new Pair<>(solutions, freshVar);
    }
    
    private Solution toSolution(Config config) {
        Term r = Substitution.applyAll(config.substitutions, Term.VAR_0);
        Pair<Witness, Witness> pair = witness ? calculateWitnesses(config, r) : new Pair<>(null, null);
        return new Solution(r, pair.a, pair.b, config.alpha1, config.alpha2);
    }
    
    private Pair<Witness, Witness> calculateWitnesses(Config config, Term r) {
        Map<Integer, Deque<Term>> W1 = new HashMap<>();
        Map<Integer, Deque<Term>> W2 = new HashMap<>();
        for (int var : r.V_named()) {
            Term varTerm = new Term(var);
            Pair<Deque<Term>, Deque<Term>> applied = AUT.applyAll(config.S, varTerm, varTerm);
            W1.put(var, applied.a);
            W2.put(var, applied.b);
        }
        return new Pair<>(new Witness(W1), new Witness(W2));
    }
}
