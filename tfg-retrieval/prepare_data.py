"""Prepara les dades de la Tate i el MoMA abans de carregar-les a Aran.

Llegeix data/raw/ i escriu data/clean/. No modifica mai els fitxers originals.
Cada transformació és deliberadament mínima i queda comptada a l'informe final.
Les decisions segueixen el «Diccionari de dades: Tate i MoMA».
"""
import argparse
import json
import re
from pathlib import Path

import pandas as pd

# Autoria desconeguda. La Tate usa «British School 18th century» per a
# obres anònimes, al MoMA «School» sol ser una institució real («Tisch School of the
# Arts»), per això cada museu té el seu patró.
ANONIM_TATE = re.compile(r"\banonymous\b|\bunknown\b|\bschool\b", re.IGNORECASE)
ANONIM_MOMA = re.compile(r"\banonymous\b|\bunknown\b|\bunidentified\b|\bvarious\b", re.IGNORECASE)
# Títols genèrics. No vol dir que falti el títol: molts artistes titulen «Untitled», posteriorment 
# s'afegeix una columna titol generic que pot prendrer valor true o false, per indicar aquests. 
TITOLS_GENERICS = {"untitled", "[title not known]", "[no title]", "blank", "[blank]"}
# Marques de data aproximada: "c.", "ca.", "circa" o un interrogant.
APROX = re.compile(r"\b(?:c|ca)\.|\bcirca\b|\?", re.IGNORECASE)
# Busca un any de quatre xifres (1000-2100) al text d'una data, amb dos afegits
# opcionals: un decenni ("1920s") o el final d'un rang ("1830-41", "1995-6")
# Només es llegeix la PRIMERA coincidència; la resta del text s'ignora 
# Només per les obres
RANG = re.compile(r"(?<!\d)(\d{4})(?!\d)(s)?(?:\s*[-–—]\s*(\d{1,4})(?!\d))?")


def parse_date(text):
    """Retorna (any_inici, any_final, aproximada) a partir d'una data en text lliure."""
    if not isinstance(text, str): #cell buida o NaN
        return None, None, False
    aproximada = bool(APROX.search(text))
    m = RANG.search(text)
    if not m:
        return None, None, aproximada # No hi ha cap any de quatre xifres
    inici = int(m.group(1))
    if not 1000 <= inici <= 2100:
        return None, None, aproximada # Any de quatre xifres fora del rang raonable
    if m.group(2):  # decenni
        final = inici + 9 # "1920s" -> 1929
    elif m.group(3) and len(m.group(3)) != 3:
        fin = m.group(3)
        # "1823-4" -> 1824, "1983-84" -> 1984, "1931-1956" -> 1956
        final = int(fin) if len(fin) == 4 else int(str(inici)[: 4 - len(fin)] + fin)
        if final < inici:  # "1999-02" -> 2002
            final += 10 ** len(fin)
    else:
        final = inici
    return inici, final, aproximada


def afegeix_dates(df, columna):
    """Afegeix any_inici, any_final i data_aproximada a partir de la columna de text de la data."""
    parsed = df[columna].map(parse_date)
    df["any_inici"] = parsed.map(lambda t: t[0]).astype("Int64")
    df["any_final"] = parsed.map(lambda t: t[1]).astype("Int64")
    df["data_aproximada"] = parsed.map(lambda t: t[2]) # bool
    return df #retorna la taula amb les noves columnes


def marca_titol_generic(df, columna):
    """Marca quines obres tenen un títol genèric i retorna quantes n'hi ha."""
    df["titol_generic"] = df[columna].isna() | df[columna].str.strip().str.lower().isin(TITOLS_GENERICS)
    return int(df["titol_generic"].sum())


def buida(df, columna, condicio):
    """Converteix en buit els valors que compleixen la condició i retorna quants n'hi havia."""
    n = int(condicio.sum())
    df.loc[condicio, columna] = pd.NA
    return n


def noms_repetits(serie):
    # Retorna quants noms hi ha repetits a la sèrie. Els valors buits no es compten com a repetició.
    return int((serie.value_counts() > 1).sum())


def llegeix(ruta):
    # utf-8-sig elimina el BOM que porta la Tate.
    return pd.read_csv(ruta, encoding="utf-8-sig", low_memory=False)


def a_text(v):
    # Evita que un enter llegit com a decimal s'escrigui "1856.0": Aran llegeix el CSV com a text.
    if pd.isna(v):
        return ""
    v = float(v)
    return str(int(v)) if v.is_integer() else str(v)


def desa(df, ruta):
    # Desa la taula a CSV amb codificació utf-8 i sense índex. Converteix els floats a text 
    # per evitar que Aran els llegeixi com a decimals.
    df = df.copy()
    for c in df.select_dtypes("float").columns:
        # Per a cada valor de la columna, aplica a_text i escriu el resultat a la mateixa columna.
        df[c] = df[c].map(a_text)
    ruta.parent.mkdir(parents=True, exist_ok=True)
    df.to_csv(ruta, index=False, encoding="utf-8")


def prepara_tate(raw, clean, informe):
    obres = llegeix(raw / "tate" / "artwork_data.csv")
    artistes = llegeix(raw / "tate" / "artist_data.csv")

    # Data de creació: es manté dateText i year tal com venen i s'hi afegeixen anys llegibles.
    obres = afegeix_dates(obres, "dateText")
    # Es guarda al informe, el nombre de dades que no tenen any llegible i el nombre de dades amb data aproximada
    informe["tate_obres_sense_any"] = int(obres["any_inici"].isna().sum())
    informe["tate_obres_data_aproximada"] = int(obres["data_aproximada"].sum())

    # year, width i height porten algun valor que no és un nombre ("no date"); queda buit.
    for c in ("year", "width", "height"):
        original = obres[c].notna().sum() 
        obres[c] = pd.to_numeric(obres[c], errors="coerce") # Converteix a nombre, deixant NaN si no es pot
        informe[f"tate_{c}_no_numeric"] = int(original - obres[c].notna().sum())

    # La Tate mesura en mm i el MoMA en cm: es passa a cm perquè siguin comparables.
    # Els valors en mm queden a data/raw; el text de dimensions continua en mm.
    for c in ("width", "height", "depth"):
        obres[c] = obres[c] / 10
        informe[f"tate_{c}_passat_a_cm"] = int(obres[c].notna().sum())
    obres.loc[obres["units"] == "mm", "units"] = "cm"

    informe["tate_obres_titol_generic"] = marca_titol_generic(obres, "title")
    informe["tate_obres_amb_artistId_inexistent"] = int(
        (~obres["artistId"].isin(artistes["id"])).sum()
    )

    # Gènere en minúscules, com al MoMA ("Male" -> "male"), marca anonimitat 
    artistes["gender"] = artistes["gender"].str.lower()
    artistes["es_anonim"] = artistes["name"].fillna("").str.contains(ANONIM_TATE)
    informe["tate_artistes_anonims"] = int(artistes["es_anonim"].sum())
    informe["tate_artistes_sense_obres"] = int((~artistes["id"].isin(obres["artistId"])).sum())
    informe["tate_artistes_noms_repetits"] = noms_repetits(artistes["name"])

    desa(obres, clean / "tate_obres.csv")
    desa(artistes, clean / "tate_artistes.csv")
    informe["tate_obres"], informe["tate_artistes"] = len(obres), len(artistes)


def prepara_moma(raw, clean, informe):
    obres = llegeix(raw / "moma" / "Artworks.csv")
    artistes = llegeix(raw / "moma" / "Artists.csv")

    # Taula pont obra-artista: ConstituentID porta els identificadors separats per coma.
    # Es parteix per identificadors i no per noms, perquè hi ha noms amb coma.
    pont = obres[["ObjectID", "ConstituentID"]].dropna().copy()
    pont["ConstituentID"] = pont["ConstituentID"].astype(str).str.split(",")
    pont = pont.explode("ConstituentID") # Cada fila amb diversos identificadors es converteix en diverses files, una per cada identificador.
    pont["ConstituentID"] = pont["ConstituentID"].str.strip().astype(int)
    pont["posicio"] = pont.groupby("ObjectID").cumcount() + 1 # Marca la posició de l'artista dins de l'obra (1, 2, 3, ...
    informe["moma_relacions_obra_artista"] = len(pont)
    informe["moma_obres_sense_artista"] = int(
        (~obres["ObjectID"].isin(pont["ObjectID"])).sum()
    )
    informe["moma_obres_amb_diversos_artistes"] = int((pont.groupby("ObjectID").size() > 1).sum())
    informe["moma_relacions_amb_artista_inexistent"] = int(
        (~pont["ConstituentID"].isin(artistes["ConstituentID"])).sum()
    )

    # Les dades de l'artista viuen a la taula d'artistes i la relació, a la taula pont.
    # A les obres hi apareixen com a llistes en una cel·la, difícils de consultar.
    # Seat Height (cm) és completament buida.
    obres = obres.drop(
        columns=["Artist", "ConstituentID", "ArtistBio", "Nationality", "Gender",
                 "BeginDate", "EndDate", "Seat Height (cm)"]
    )

    obres = afegeix_dates(obres, "Date")
    informe["moma_obres_sense_any"] = int(obres["any_inici"].isna().sum())
    informe["moma_obres_data_aproximada"] = int(obres["data_aproximada"].sum())
    # La Tate només dona l'any d'ingrés; aquí se n'extreu l'any per poder comparar.
    obres["any_adquisicio"] = pd.to_datetime(obres["DateAcquired"], errors="coerce").dt.year.astype("Int64")

    informe["moma_obres_classificacio_not_assigned"] = buida(
        obres, "Classification", obres["Classification"] == "(not assigned)"
    )
    # Alçada o amplada 0 vol dir «no informat». Profunditat i durada 0 són ambigus
    # (obra plana, obra sense durada) i es deixen tal com venen.
    for c in ("Height (cm)", "Width (cm)"):
        informe[f"moma_obres_{c.split()[0].lower()}_zero"] = buida(obres, c, obres[c] == 0)
    informe["moma_obres_depth_zero_sense_tocar"] = int((obres["Depth (cm)"] == 0).sum())
    informe["moma_obres_duration_zero_sense_tocar"] = int((obres["Duration (sec.)"] == 0).sum())
    # OnView porta cometes dins del valor: "\"MoMA, Floor 4, 419\"".
    obres["OnView"] = obres["OnView"].str.strip('"')
    informe["moma_obres_titol_generic"] = marca_titol_generic(obres, "Title")

    # Un 0 vol dir "desconegut". Per a EndDate també pot voler dir "encara viu";
    # no es pot distingir amb aquestes dades.
    for c in ("BeginDate", "EndDate"):
        informe[f"moma_artistes_{c}_zero"] = int((artistes[c] == 0).sum())
        artistes[c] = artistes[c].where(artistes[c] != 0).astype("Int64")
    informe["moma_artistes_nationality_unknown"] = buida(
        artistes, "Nationality", artistes["Nationality"] == "Nationality unknown"
    )

    artistes["es_anonim"] = artistes["DisplayName"].fillna("").str.contains(ANONIM_MOMA)
    informe["moma_artistes_anonims"] = int(artistes["es_anonim"].sum())
    informe["moma_artistes_noms_repetits"] = noms_repetits(artistes["DisplayName"])
    informe["moma_artistes_wiki_qid_repetits"] = noms_repetits(artistes["Wiki QID"])

    desa(obres, clean / "moma_obres.csv")
    desa(artistes, clean / "moma_artistes.csv")
    desa(pont, clean / "moma_obra_artista.csv")
    informe["moma_obres"], informe["moma_artistes"] = len(obres), len(artistes)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--raw", type=Path, default=Path("data/raw"))
    p.add_argument("--clean", type=Path, default=Path("data/clean"))
    args = p.parse_args()

    informe = {}
    prepara_tate(args.raw, args.clean, informe)
    prepara_moma(args.raw, args.clean, informe)

    ruta = args.clean / "informe_preparacio.json"
    ruta.write_text(json.dumps(informe, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(informe, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()