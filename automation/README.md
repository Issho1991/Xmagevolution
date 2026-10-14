# Aggiornamenti ufficiali automatici

Il launcher 0.3.1 e l'indirizzo del catalogo restano compatibili. Non occorre reinstallare il launcher per questo flusso.

## Flusso

1. Un controllo programmato esterno a GitHub cerca una nuova release stabile ufficiale ogni sei ore.
2. Se la base ufficiale è più recente di quella nel catalogo firmato, aggiorna `automation/request.json`. Il commit avvia il workflow `automatic-upstream.yml`; il workflow verifica autonomamente la release ufficiale più recente.
3. Il workflow unisce i sorgenti Java personalizzati di Lab 10.0 alla nuova base con un merge a tre vie. Un conflitto, un file rimosso o una struttura non riconosciuta interrompe il lavoro.
4. Compila contro il runtime ufficiale con digest SHA-256 verificato, esegue almeno 147 test attivi e verifica installazione, backup, avvio del server e ritorno alla versione precedente in una copia isolata.
5. Un job separato, con permesso di pubblicazione, crea un candidato immutabile `lab-auto-u<release-id>`. Il job di compilazione non riceve credenziali di scrittura o chiavi di firma. I candidati non sono ancora proposti dal launcher.
6. Il controllo programmato verifica che il workflow sia concluso con successo, controlla provenienza e contenuto del pacchetto scaricato e prepara il catalogo con `promote.py`. La chiave di firma viene usata fuori da GitHub.
7. Pubblica il catalogo firmato con un numero di pubblicazione crescente, conservando il precedente in archivio. Soltanto allora il launcher rende disponibile il nuovo aggiornamento.

L'installazione sul PC resta avviata dall'utente con **Controlla aggiornamenti → Scarica e aggiorna Laboratorio**. Il sistema non interrompe una partita né installa software sul PC da remoto.

## Arresti previsti

Conflitti, cambi delle API non compatibili, errori di compilazione, test falliti o saltati oltre la soglia, avvio del server non riuscito, firma errata, download alterato o provenienza non verificabile impediscono l'aggiornamento del catalogo. Il controllo programmato segnala il blocco senza ripetere notifiche identiche. La release funzionante resta disponibile. Le modifiche che richiedono ragionamento o un cambio dello schema dati richiedono intervento di sviluppo; non vengono risolte eliminando personalizzazioni o indebolendo i test.

Le verifiche automatiche non equivalgono a una partita grafica completa su Windows e non certificano l'assenza di ogni difetto di gioco.

## Riproduzione e controlli

```sh
python3 -m unittest discover -s automation/tests -v
python3 automation/pipeline.py --replay --work auto-work
```

`--replay` ripete l'integrazione anche sulla release corrente. Un replay di una versione già presente nel catalogo non può essere pubblicato come nuovo aggiornamento. Nessun test usa mazzi, report o preferenze dell'utente.

Il job di pubblicazione si avvia soltanto se tutti i controlli del job precedente sono riusciti e la release è più recente. `promote.py` è una seconda barriera: rifiuta replay, downgrade, job incompleti, prove incoerenti, hash diversi, inventari incompleti e chiavi non corrispondenti al launcher.

## Promozione di un candidato

Da un checkout aggiornato di `main`, dopo verifica del run:

```sh
python3 automation/promote.py --tag lab-auto-u<release-id> --work promotion-work
python3 automation/promote.py --tag lab-auto-u<release-id> --work promotion-work --key /percorso/esterno/chiave.pem
```

Il primo comando verifica senza firmare; il secondo produce `promotion-work/catalog.properties` e `promotion.json`. Nessuno dei due scrive su GitHub. La chiave deve essere quella già associata al launcher; non deve entrare in commit, log, pacchetti o artifact.

Prima del commit finale controllare nuovamente il ramo e il digest del catalogo: devono coincidere con il checkout usato per la verifica e `previousCatalogSha256`. Pubblicare insieme l'archivio del catalogo precedente, il catalogo nuovo, le prove e lo stato dell'operazione. Usare un aggiornamento del ramo con il commit precedente atteso, senza force. In caso di modifica concorrente rifare il controllo sul nuovo stato, mai sovrascrivere.

Dopo la pubblicazione scaricare il catalogo dall'indirizzo usato dal launcher, verificarne firma e identità e controllare il download del pacchetto. Solo allora notificare che l'aggiornamento è disponibile. Se il run è ancora in corso, proseguire al controllo successivo senza presentarlo come completato.

## Stato persistente e ripresa

Il catalogo firmato è l'autorità sulla versione distribuita. `request.json` identifica l'ultima release richiesta; i run e le release GitHub permettono di riprendere una verifica interrotta. `updates/automation-state.json` registra l'ultimo esito, la release e l'eventuale blocco già notificato. I controlli senza novità non generano commit o notifiche. I pacchetti pubblicati non si sovrascrivono mai con contenuti diversi.

Il controllo programmato richiede che le connessioni GitHub e dell'archivio privato rimangano disponibili. Se un accesso o un'approvazione viene bloccato, segnala il motivo e mantiene il catalogo corrente. Non aggira limiti o controlli di accesso.
