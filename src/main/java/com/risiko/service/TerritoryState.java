package com.risiko.service;

/**
 * Stato mutabile di un territorio durante la simulazione in-memory.
 * NON è un'entità JPA — vive solo durante la generazione della simulazione.
 */
public class TerritoryState {
    private String colore;
    private int    armate;

    public TerritoryState(String colore, int armate) {
        this.colore  = colore;
        this.armate  = Math.max(0, armate);  // era Math.max(1, armate)
    }

    public void removeArmate(int n) { this.armate = Math.max(0, this.armate - n); }  // era Math.max(1, ...)

    public String getColore()  { return colore; }
    public void   setColore(String colore) { this.colore = colore; }

    public int  getArmate()    { return armate; }
    public void setArmate(int armate) { this.armate = Math.max(0, armate); }
    public void addArmate(int n)    { this.armate += n; }

    /** Deep copy per salvare snapshot senza riferimenti condivisi. */
    public TerritoryState copy() {
        return new TerritoryState(this.colore, this.armate);
    }

    @Override
    public String toString() {
        return colore + "(" + armate + ")";
    }
}
