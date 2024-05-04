package at.jku.risc.uarau.algorithm;

import at.jku.risc.uarau.data.ProximityRelation;
import at.jku.risc.uarau.data.Term;
import org.junit.platform.commons.util.StringUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ProximityMap {
    private final Map<String, Set<ProximityRelation>> proximityClasses = new HashMap<>();
    private final Set<ProximityRelation> relations = new HashSet<>();
    final Map<String, Integer> arities = new HashMap<>();
    
    public void add(ProximityRelation pr, float lambda) {
        if (pr == null || pr.proximity() < 0.0f || pr.proximity() > 1.0f) {
            throw new IllegalArgumentException();
        }
        // optimization: these will never come into play
        if (pr.proximity() < lambda) {
            System.out.println(STR."Discarding relation with proximity [\{pr.proximity()}] < λ [\{lambda}]");
            return;
        }
        Set<ProximityRelation> fClass = proximityClasses.computeIfAbsent(pr.f(), _ -> new HashSet<>());
        Set<ProximityRelation> gClass = proximityClasses.computeIfAbsent(pr.g(), _ -> new HashSet<>());
        fClass.add(pr);
        gClass.add(pr);
        relations.add(pr);
    }
    
    // TODO this needs unit tests
    public Set<String> commonApproximates(Set<Term> terms) {
        // for some term s, find all its approximates
        Term s = terms.stream().findAny().orElseThrow();
        terms.remove(s);
        
        Set<String> common = proximityClass(s.head).stream()
                .map(relation -> relation.getOther(s.head))
                .collect(Collectors.toSet());
        
        // retain approximates which all other terms have in common
        for (Term t : terms) {
            Set<String> tSet = proximityClass(t.head).stream()
                    .map(relation -> relation.getOther(t.head))
                    .collect(Collectors.toSet());
            
            common.retainAll(tSet);
        }
        return common;
    }
    
    public Set<ProximityRelation> proximityClass(String head) {
        return proximityClasses.getOrDefault(head, new HashSet<>());
    }
    
    @Override
    public String toString() {
        if (proximityClasses.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder(" ");
        for (ProximityRelation pr : relations) {
            sb.append(pr.toString()).append("\n       ");
        }
        return STR."{\{sb.delete(sb.length() - 8, sb.length()).toString()} }";
    }
    
    // TODO non-parsing construction (for Problem constructor)
    
    private ProximityMap() {
    }
    
    // relations looks like this:  f g {<arg-map>} [<proximity>]  and are separated by ';'
    // <arg-map> looks like this:  (1,1), (2,3), (3,1), ...  (with surrounding '{}')
    // <proximity> is a float between 0.0 and 1.0            (with surrounding '[]')
    
    static ProximityMap parse(String map, float lambda) {
        var proximityMap = new ProximityMap();
        String[] relations = map.split(";");
        for (String relation : relations) {
            if (StringUtils.isNotBlank(relation)) {
                proximityMap.add(ProximityRelation.parse(relation), lambda);
            }
        }
        return proximityMap;
    }
}
