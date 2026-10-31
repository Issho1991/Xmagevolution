# Aggiornamenti autonomi del Laboratorio

Il controllo è eseguito da GitHub Actions ogni sei ore. Non dipende da ChatGPT, dal PC acceso o da un promemoria. La pubblicazione automatica richiede la configurazione iniziale della firma descritta sotto. Senza chiave, il servizio registra `signing_not_configured` e conserva il catalogo corrente.

## Flusso

1. `automatic-upstream.yml` legge la release stabile ufficiale più recente di `magefree/mage`, esclude draft e prerelease e verifica tag, commit, asset e SHA-256. Il catalogo firmato determina la versione distribuita; `request.json` rimane una traccia storica, non richiede più modifiche per ogni release.
2. Se la base ufficiale è più recente, integra le personalizzazioni di Lab 10.0 / IA6 con un merge a tre vie. Conflitti, file rimossi o strutture sconosciute interrompono il lavoro.
3. Compila contro il runtime ufficiale verificato, esegue almeno 147 test attivi e controlla installazione, backup, avvio del server e ritorno alla versione precedente in una copia isolata.
4. Un job separato pubblica il candidato immutabile `lab-auto-u<release-id>`. Il processo di compilazione e test non riceve la chiave o credenziali di scrittura.
5. Alla conclusione, `publish-channel.yml` controlla repository, ramo `main`, percorso del workflow, commit e risultato. `channel.py` esegue prima `promote.py` senza chiave: verifica la provenienza del candidato, la release ufficiale, il pacchetto riscaricato e l'intero inventario.
6. Solo dopo questa verifica, la fase di firma usa il secret dell'ambiente `lab-release`. La chiave viene scritta in una cartella temporanea privata, con permessi 600, verificata contro la chiave pubblica incorporata nel launcher e rimossa anche in caso di errore.
7. Il nuovo catalogo, il precedente archiviato, la prova e lo stato vengono pubblicati in un unico commit. La testa di `main` e l'hash del catalogo vengono ricontrollati; il riferimento viene aggiornato senza force. Una modifica concorrente interrompe l'operazione e il controllo successivo riparte dal nuovo stato.
8. Il catalogo viene scaricato dall'indirizzo effettivo del launcher e la firma, il seriale e il contenuto vengono verificati nuovamente.

Replay, downgrade, prove incoerenti, pacchetti alterati o archivi già pubblicati con contenuto differente sono rifiutati. I test e le personalizzazioni non vengono indeboliti per superare un blocco. Un errore mantiene disponibile l'ultima versione verificata; il controllo successivo può ritentare. I problemi di compatibilità richiedono una correzione di sviluppo.

### Compatibilità XMage 1.4.63

La registrazione del ripristino in `GameImpl` avviene dopo il ripristino dello stato e dell'ordine dei giocatori. Questo permette al merge a tre vie di conservare la nuova modalità ufficiale `restoreStateKeepingStack`, insieme ai checkpoint dei report e al divieto di annullare un'eliminazione. Tre regressioni aggiuntive verificano l'identità della pila durante un ripristino di un'azione di mana, il ripristino ordinario e il blocco attraverso un'eliminazione; gli eventi annullati restano esclusi dall'analisi. I pacchetti già pubblicati restano immutati. Anche le modifiche all'overlay avviano ora la verifica completa su `main`.

## Configurazione iniziale della firma

Da **Settings → Environments** del repository:

1. Crea o apri l'ambiente **`lab-release`**.
2. In **Deployment branches and tags**, seleziona i rami/tag specifici e consenti esclusivamente il **branch `main`**. Non aggiungere una regola per i tag.
3. Per la pubblicazione autonoma, l'ambiente non deve richiedere un'approvazione manuale per ogni esecuzione. Non rimuovere protezioni già impostate senza una decisione esplicita del proprietario.
4. Aggiungi come **Environment secret**, non come file del repository, **`LAB_CATALOG_PRIVATE_KEY`**: il contenuto PEM completo della chiave originale nell'archivio privato `Materiale_firma_Laboratorio_PRIVATO.zip`. Non generare una nuova chiave: il launcher riconosce quella già incorporata.
5. In **Actions → Automatic official XMage integration → Run workflow**, scegli `main`. Dopo il controllo, anche **Publish verified Laboratorio channel** deve riuscire. Se non c'è una nuova release, il catalogo resta uguale e lo stato passa a `current` solo se la chiave configurata corrisponde a quella del launcher.

La chiave resta un segreto custodito da GitHub per la sola fase di firma. Non deve essere incollata in chat, commit, issue, note di release, log o artifact. Non è usata per scaricare o eseguire codice ufficiale. Chi può modificare i workflow su `main` deve essere considerato affidabile; proteggere gli accessi al repository.

## Uso nell'app

Il launcher 0.3.1 continua a scaricare i pacchetti dal catalogo esistente. Il launcher **0.3.2** aggiunge, in **Aggiornamenti**, la sezione **Preparazione automatica degli aggiornamenti** con la data dell'ultimo controllo e l'eventuale blocco. Dopo 72 ore senza controlli registrati mostra un avviso. Lo stato è solo informativo: soltanto il catalogo firmato autorizza il download/installazione.

L'utente preme **Controlla aggiornamenti**, poi **Scarica e aggiorna Laboratorio**. L'installazione sul PC resta manuale; il servizio non interrompe partite. Mazzi, report e registro condiviso non fanno parte di queste operazioni. Non vengono creati promemoria o notifiche ChatGPT.

Il controllo del servizio avviene con il pulsante dell'app. All'apertura viene mostrato l'ultimo stato salvato, con la sua data; in assenza di rete rimane consultabile. Il nuovo launcher si installa una volta sostituendo `launcher/laboratorio-launcher.jar` a launcher, client e server chiusi, come indicato nello ZIP.

## Stato, limiti e verifiche

`updates/automation-state.json` registra lo stato del servizio e la data di ogni controllo con un piccolo commit, anche se il catalogo non cambia. Non contiene dati personali né chiavi. Gli aggiornamenti del solo stato non attivano un'altra integrazione. I risultati di run precedenti non sovrascrivono quelli di run più recenti.

Gli orari di GitHub Actions possono subire ritardi; GitHub può disattivare workflow programmati in repository pubblici inattivi. La data nell'app rende visibile un eventuale arresto. Se il workflow di pubblicazione non parte per un blocco dell'ambiente o dei permessi, lo stato diventa obsoleto e il launcher lo segnala. Le preferenze personali di notifica di GitHub rimangono gestite dall'account GitHub.

Le letture delle API usano il `GITHUB_TOKEN` temporaneo del workflow, esposto come `GH_TOKEN`: nel job di integrazione ha soltanto `contents: read`. Non serve un nuovo secret. Il token è inviato esclusivamente a `https://api.github.com` e non viene inoltrato nei redirect o ai server dei pacchetti. Anche il controllo del canale usa il token nel processo principale; il processo separato di promozione continua a non ereditare token o la chiave in formato testo.

In caso di limite API (`403` riconosciuto come rate limit o `429`), le letture rispettano `Retry-After` e `X-RateLimit-Reset`, con al massimo tre tentativi e 180 secondi complessivi di attesa. Se il limite richiede più tempo, il controllo fallisce senza anticipare il reset e riprova alla successiva esecuzione programmata. Gli errori di autenticazione e permesso restano errori; nessun controllo di firma, provenienza o test viene saltato. Il riferimento per i tempi di attesa è la [documentazione GitHub sui limiti API](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api).

```sh
python3 -m unittest discover -s automation/tests -v
python3 automation/build_launcher.py --output launcher-build
python3 automation/pipeline.py --replay --work auto-work
```

`--replay` ripete anche compilazione, test e verifica installazione sulla release corrente, senza promuoverla come nuova. Le verifiche automatiche non equivalgono a una partita grafica completa su Windows.

Per una promozione manuale di emergenza, da un checkout aggiornato:

```sh
python3 automation/promote.py --tag lab-auto-u<release-id> --work promotion-work
python3 automation/promote.py --tag lab-auto-u<release-id> --work promotion-work --key /percorso/privato/chiave.pem
```

Questi due comandi preparano i file senza scrivere su GitHub. La pubblicazione deve mantenere le stesse verifiche sul ramo e su `previousCatalogSha256`, la scrittura atomica di catalogo/archivio/prova/stato e il controllo finale dell'URL del launcher.

## Launcher 0.4.0

L’interfaccia separa **Gioca**, **Aggiornamenti** e **Impostazioni**. La prima pagina mostra versione del Laboratorio, base XMage e IA attive; mazzi e report sono accessibili senza entrare nella diagnostica. Java, canale, cataloghi locali, importazione ZIP, versioni precedenti e backup restano nelle impostazioni. I riferimenti fissi alle vecchie versioni sono stati rimossi.

Il controllo parte all’apertura in un thread separato e non impedisce di giocare; si può disabilitare dalla pagina Aggiornamenti. Nessuna installazione parte da sola. Uno stato “Tutto aggiornato” richiede un controllo riuscito sia del catalogo del gioco sia del launcher; un errore di rete non viene presentato come assenza di aggiornamenti. Il catalogo incorporato e quelli salvati vengono sempre verificati. Il pulsante di aggiornamento del gioco non propone una versione precedente a quella attiva: il ritorno intenzionale resta in Versioni e backup. Una versione aggiornata già scaricata può essere attivata direttamente, con verifica e backup.

### Aggiornamento del launcher dall’app

Il launcher usa un **catalogo distinto** in `updates/launcher.properties`, con prodotto `xmage-laboratorio-launcher`, versione, seriale derivato dalla versione, SHA-256 e dimensione del JAR, commit sorgente e URL immutabile della release `launcher-v<versione>`. La firma RSA è verificata con la stessa chiave pubblica incorporata. Il catalogo del gioco non autorizza codice del launcher. Versioni precedenti, contenuti diversi con la stessa versione, URL estranei e una chiave diversa nel nuovo JAR sono rifiutati.

**Aggiorna launcher e riavvia** richiede conferma e client/server chiusi. Dopo il download verificato, un processo separato eseguito dalla copia del launcher precedente attende il rilascio del blocco di sessione. Conserva `backup-launcher.jar` nella cartella `launcher/aggiornamento-launcher/update-*`, sostituisce atomicamente il solo JAR e avvia la nuova versione. Il nuovo launcher deve confermare l’apertura entro 30 secondi. In caso di mancato avvio il processo tenta il ripristino e il riavvio del precedente; la copia resta disponibile anche per il recupero manuale. Un disco che non supporta la sostituzione atomica interrompe l’operazione. L’assistente di aggiornamento non modifica gioco, Java, impostazioni, mazzi o report.

La migrazione da 0.3.x richiede ancora **una sostituzione manuale iniziale** del JAR, con lo ZIP `Launcher_0_4_0_per_XMage_Lab.zip`. Gli aggiornamenti successivi del launcher saranno disponibili dai suoi pulsanti quando pubblicati nel catalogo firmato.

### Pubblicazione del launcher

`release-launcher.yml` compila e verifica il launcher su **Ubuntu e Windows**, senza chiavi. I controlli comprendono firma, replay/downgrade, alterazione del JAR, ripristino e un passaggio reale fra JVM con blocco del file, conferma del nuovo avvio e simulazione di avvio fallito. Il test di passaggio usa applicazioni innocue create in cartelle temporanee; non avvia partite reali. Le anteprime sono prodotte con dati sintetici.

Solo dopo il successo di entrambi i job, il job separato nell’ambiente `lab-release` usa il secret già configurato. `publish_launcher.py` controlla provenienza del run e risultati delle piattaforme, verifica ZIP/JAR e corrispondenza della chiave, pubblica asset immutabili, li riscarica e ne verifica gli hash. Pubblica poi il catalogo firmato e l’eventuale copia del precedente in un commit senza force e verifica il catalogo dall’URL effettivo. Non modifica `updates/catalog.properties` o lo stato del canale del gioco. Le variazioni concorrenti di codice o del catalogo interrompono la pubblicazione; gli aggiornamenti del solo stato non vengono sovrascritti.

Ogni modifica pubblicata ai byte del launcher richiede una nuova versione coerente in `LauncherInfo.java` e `build_launcher.py`. Un rerun non può sovrascrivere gli asset di una versione con contenuti diversi. Questo flusso usa l’ambiente e la chiave esistenti; non richiede nuovi promemoria o secret.
