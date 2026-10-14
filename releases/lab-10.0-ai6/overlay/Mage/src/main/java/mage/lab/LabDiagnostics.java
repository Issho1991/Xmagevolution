package mage.lab;

import java.util.Locale;

/** Diagnostics describe observed facts; neither alter eligibility nor infer an unobserved cause. */
final class LabDiagnostics {
    static String seconds(long nanos) { return String.format(Locale.ITALY, "%.3f", nanos / 1.0e9); }
    static String timing(LabGame.Timing t) {
        StringBuilder b = new StringBuilder().append(t.calls).append(" chiamate; tempo trascorso totale ")
                .append(seconds(t.totalNanos)).append(" s, massimo ").append(seconds(t.maxNanos)).append(" s");
        if (t.measurementVersion < 2 || t.measuredCalls == 0) {
            b.append(". CPU e attese non disponibili in questa registrazione");
            if (t.maxNanos >= 60000000000L) b.append(". Picco prolungato: dai vecchi dati non si distingue calcolo, attesa o sospensione");
        } else {
            b.append(". CPU del thread: ");
            if (t.cpuMeasuredCalls == 0) b.append("non disponibile");
            else b.append(seconds(t.totalCpuNanos)).append(" s (copertura ").append(t.cpuMeasuredCalls).append('/').append(t.calls)
                    .append(" chiamate; massimo ").append(seconds(t.maxCpuNanos)).append(" s); tempo senza CPU del thread ")
                    .append(seconds(t.totalNonCpuNanos)).append(" s sulle chiamate misurate");
            b.append(". Attesa esplicita di risposta: ").append(seconds(t.totalResponseWaitNanos)).append(" s (copertura ")
                    .append(t.measuredCalls).append('/').append(t.calls).append(")")
                    .append(". Orologio civile: totale ").append(seconds(t.totalWallMillis * 1000000L)).append(" s, massimo ")
                    .append(seconds(t.maxWallMillis * 1000000L)).append(" s")
                    .append(". Discontinuità degli orologi: ").append(t.clockDiscontinuityCalls)
                    .append("; chiamate lunghe con poco tempo CPU non spiegate dall'attesa di risposta: ").append(t.longNonCpuCalls);
        }
        if(t.measurementVersion>=3) {
            b.append(". Ricerche: ").append(t.searchCalls).append("; CPU ricerca ").append(seconds(t.searchCpuNanos))
                .append(" s (copertura ").append(t.searchCpuMeasured).append('/').append(t.searchCalls).append(")")
                .append("; attesa del risultato ").append(seconds(t.searchWaitNanos)).append(" s, di cui coda ")
                .append(seconds(t.searchQueueNanos)).append(" s; timeout ").append(t.searchTimeouts)
                .append("; misure parziali al ritorno ").append(t.searchPartial).append("; errori/interruzioni ").append(t.searchFailures);
        } else if(t.measurementVersion==2) b.append(". CPU della ricerca su thread separato non registrata in questa versione");
        return b.append('.').toString();
    }
    static String exit(String reason, String source) {
        String label;
        if (reason == null) return "motivo non registrato";
        switch (reason) {
            case "LIFE_ZERO": label = "vite a zero o meno"; break;
            case "POISON": label = "soglia di segnalini veleno raggiunta"; break;
            case "EMPTY_LIBRARY": label = "tentativo di pescare dalla biblioteca vuota"; break;
            case "CONCEDE": label = "concessione della manche"; break;
            case "MATCH_QUIT": label = "abbandono dell'incontro"; break;
            case "TIMER_TIMEOUT": label = "tempo di gioco esaurito"; break;
            case "IDLE_TIMEOUT": label = "limite di inattività raggiunto"; break;
            case "CARD_EFFECT": label = "effetto di una carta"; break;
            case "OPPONENT_WIN": label = "vittoria dichiarata di un avversario"; break;
            case "CLIENT_EXIT": label = "disconnessione richiesta dal client con rimozione dai tavoli"; break;
            case "ADMIN_DISCONNECT": label = "rimozione da parte dell'amministratore"; break;
            case "SESSION_EXPIRED": label = "sessione scaduta con rimozione dai tavoli"; break;
            case "DISCONNECTED": label = "rimozione per disconnessione"; break;
            default: label = "motivo non registrato";
        }
        return label + (source == null ? "" : "; fonte: " + source);
    }
    static String poison(Integer value) { return value == null ? "veleno non registrato" : "segnalini veleno " + value; }
    static final String TIMING_NOTE = "La CPU del chiamante e quella della ricerca sono distinte; non rappresentano tutti i thread del processo. "
            + "Le misure parziali includono solo la ricerca fino al ritorno del chiamante. Il tempo senza CPU del chiamante può includere attese, lavoro su altri thread, garbage collection o sospensione: non prova da solo che il PC fosse sospeso. "
            + "Le attese di risposta e di ricerca sono già incluse nel tempo trascorso, non vanno sommate alla durata. "
            + "Le discontinuità possono dipendere anche da correzioni dell'orologio; i totali civili sommano solo intervalli non negativi. "
            + "Sono inclusi i tempi di azioni poi annullate; non è la durata della manche.";
    private LabDiagnostics() { }
}
