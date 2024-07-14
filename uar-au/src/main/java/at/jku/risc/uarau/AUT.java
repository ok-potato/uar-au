package at.jku.risc.uarau;

import java.util.Collections;
import java.util.Set;

public class AUT {
    public final int var;
    public final Set<Term> T1, T2;
    
    public AUT(int var, Set<Term> T1, Set<Term> T2) {
        this.var = var;
        this.T1 = T1;
        this.T2 = T2;
    }
    
    public AUT(int var, Term T1, Term T2) {
        this(var, Collections.singleton(T1), Collections.singleton(T2));
    }
    
    @Override
    public String toString() {
        return String.format("%s: %s =^= %s", var, T1, T2);
    }
}
