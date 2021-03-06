package org.biojava3.structure.quaternary.analysis;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Set;

import org.biojava.bio.structure.PDBHeader;
import org.biojava.bio.structure.Structure;
import org.biojava.bio.structure.StructureException;
import org.biojava.bio.structure.align.util.AtomCache;
import org.biojava.bio.structure.io.FileParsingParameters;
import org.biojava.bio.structure.io.mmcif.ChemCompGroupFactory;
import org.biojava.bio.structure.io.mmcif.DownloadChemCompProvider;
import org.biojava.bio.structure.xtal.SpaceGroup;
import org.biojava3.structure.StructureIO;
import org.biojava3.structure.dbscan.GetRepresentatives;
import org.biojava3.structure.quaternary.core.QuatSymmetryDetector;
import org.biojava3.structure.quaternary.core.QuatSymmetryParameters;
import org.biojava3.structure.quaternary.core.QuatSymmetryResults;
import org.biojava3.structure.quaternary.core.Subunits;
import org.biojava3.structure.quaternary.misc.BioassemblyCheck;
import org.biojava3.structure.quaternary.misc.FindPdbRepresentatives;
import org.biojava3.structure.quaternary.misc.PdbBlastHit;
import org.biojava3.structure.quaternary.misc.ProteinComplexSignature;
import org.biojava3.structure.quaternary.utils.BlastClustReader;

public class BioAssemblyAnalyzer implements Runnable {
	public static final String SERVICELOCATION="http://www.rcsb.org/pdb/rest/postBLAST";
	private AtomCache cache = null;
	private static String RESULT_DIR = "/Users/peter/Results/BioAssemblyAnalyzer/";


	public BioAssemblyAnalyzer () {
		initializeCache();
	}

	public static void main(String[] args) {
		new BioAssemblyAnalyzer().run();
	}

	public void run() {
		String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(Calendar.getInstance().getTime());

		System.out.println("Reading blastclust files");

		BlastClustReader reader95 = new BlastClustReader(95);
		BlastClustReader reader30 = new BlastClustReader(30);


		PrintWriter out = null;
		PrintWriter error = null;

		try {
			out = new PrintWriter(new FileWriter(RESULT_DIR + timeStamp + "_symm.csv"));
			error = new PrintWriter(new FileWriter(RESULT_DIR + timeStamp + "_error.txt"));
		} catch (IOException e1) {
			e1.printStackTrace();
			System.exit(-1);
		}


		long t1 = System.nanoTime();

		int success = 0;
		int proteins = 0;
		int failure = 0;

		String header = "pdbId,bioassembly,local,pseudostoichiometric,stoichiometry,pseudosymmetric,pointgroup,order," +
				"lowSymmetry,minidentity,maxidentity,subunitrmsd,rmsd,tm,minrmsd,maxrmsd,mintm,maxtm,rmsdintra,tmintra,symdeviation,subunits,nucleiacids,cacount,time,signature95,stoich95,signature30,stoich30,spacegroup";
		out.println(header);

		QuatSymmetryParameters parameters = new QuatSymmetryParameters();

//		parameters.setVerbose(true);
//		parameters.setRmsdThreshold(7.0);
//		parameters.setAngleThreshold(90);
//		parameters.setHelixRmsdThreshold(2.0);

		Set<String> set = GetRepresentatives.getAll();

		// set skip to true to restart calculation with a specified PDB ID
		boolean skip = false;
		String restartId = "10MH";

		for (String pdbId: set) {
			if (skip && pdbId.equals(restartId)) {
				skip = false;
			} 
			if (skip) {
				continue;
			}

			System.out.println("------------- " + pdbId  + "-------------");

			StructureIO.setAtomCache(cache);
			int bioAssemblyCount = StructureIO.getNrBiologicalAssemblies(pdbId);

			int bioAssemblyId = 0;
			System.out.println("Bioassemblies: " + bioAssemblyCount);
			if (bioAssemblyCount > 0) {
				bioAssemblyId = 1;
			}

			// TODO for 
//			bioAssemblyId = 0;
			System.out.println("bioAssemblyId: " + bioAssemblyId);
			//			for (int i = 0; i < bioAssemblyCount; i++) {	
			Structure structure = null;
			try {
				structure = StructureIO.getBiologicalAssembly(pdbId, bioAssemblyId);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				error.println(pdbId + "[" + bioAssemblyId + "]: " + e.getMessage());
				error.flush();
			} catch (StructureException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				error.println(pdbId + "[" + bioAssemblyId + "]: " + e.getMessage());
				error.flush();
			}

			long ts1 = System.nanoTime(); 	

			try {
				SpaceGroup spaceGroup = null;
				float resolution = 0.0f;
				if (structure != null) {
					spaceGroup = structure.getCrystallographicInfo().getSpaceGroup();
					 structure.getCrystallographicInfo().getA();
					PDBHeader pdbHeader = structure.getPDBHeader();
					resolution = pdbHeader.getResolution();	
				}
				FindPdbRepresentatives finder = new FindPdbRepresentatives(structure);
				List<PdbBlastHit> representatives = finder.findBestBlastHits();
				
				
				QuatSymmetryDetector detector = new QuatSymmetryDetector(structure, parameters);

				if (detector.hasProteinSubunits()) {	
					long ts2 = System.nanoTime();
					int time = Math.round((float)(ts2-ts1)/1000000.0f);
					
					// save global symmetry results
					List<QuatSymmetryResults> globalResults = detector.getGlobalSymmetry();			
					BioassemblyCheck check = new BioassemblyCheck();

					for (QuatSymmetryResults result: globalResults) {
						if (! result.isLocal() && ! result.getSubunits().isPseudoStoichiometric()) {
							check.BioassemblyCheck(representatives, result.getSubunits().getStoichiometry(), result.getSymmetry());
						}
					}
										
					printToCsv(reader95, reader30, out, pdbId,
							bioAssemblyId, time, globalResults, spaceGroup);
					
					// save local symmetry results
					for (List<QuatSymmetryResults> localResults: detector.getLocalSymmetries()) {
						printToCsv(reader95, reader30, out, pdbId,
								bioAssemblyId, time, localResults, spaceGroup);
					}
					proteins++;
				}
				success++;
				out.flush();
			} catch (Exception e) {
				failure++;
				e.printStackTrace();
				error.println(pdbId + "[" + bioAssemblyId + "]: " + e.getMessage());
				error.flush();
			}
		}
		long t2 = System.nanoTime();

		System.out.println("PDBs succeeded: " + success);
		System.out.println("PDBs failed   : " + failure);
		System.out.println("Proteins      : " + proteins);
		System.out.println("Total structure: " + set.size());
		System.out.println("Cpu time: " + (t2-t1)/1000000 + " ms.");

		out.close();
		//		out1.flush();
		error.close();
	}

	private void printToCsv(BlastClustReader reader95,
			BlastClustReader reader30, PrintWriter out, String pdbId,
			int bioAssemblyId, int time, List<QuatSymmetryResults> resultsList, SpaceGroup spaceGroup) {
		
		for (QuatSymmetryResults results: resultsList) {
			ProteinComplexSignature s95 = new ProteinComplexSignature(pdbId, results.getSubunits().getChainIds(), reader95);
			String signature95 = s95.getComplexSignature();
			String stoich95 = s95.getComplexStoichiometry();
			ProteinComplexSignature s30 = new ProteinComplexSignature(pdbId, results.getSubunits().getChainIds(), reader30);
			String signature30 = s30.getComplexSignature();
			String stoich30 = s30.getComplexStoichiometry();
			
			int order = 1;
			if (!results.getSymmetry().equals("H")) {
				order = results.getRotationGroup().getOrder();
			}

			out.println("PDB" + pdbId +"," + bioAssemblyId + "," + results.isLocal() +
					"," + results.getSubunits().isPseudoStoichiometric() +
					"," + results.getSubunits().getStoichiometry() +
					"," + results.getSubunits().isPseudoSymmetric() +
					"," + results.getSymmetry() +
					"," + order + 
					"," + isLowSymmetry(results) +
					"," + Math.round(results.getSubunits().getMinSequenceIdentity()*100.0) +
					"," + Math.round(results.getSubunits().getMaxSequenceIdentity()*100.0) +
					"," + (float) results.getScores().getRmsdCenters() +
					"," + (float) results.getScores().getRmsd() +
					"," + (float) results.getScores().getTm() +
					"," + (float) results.getScores().getMinRmsd() +
					"," + (float) results.getScores().getMaxRmsd() +
					"," + (float) results.getScores().getMinTm() +
					"," + (float) results.getScores().getMaxTm() +
					"," + (float) results.getScores().getRmsdIntra() +
					"," + (float) results.getScores().getTmIntra() +
					"," + (float) results.getScores().getSymDeviation() +
					"," + results.getSubunits().getSubunitCount() +
					"," + results.getNucleicAcidChainCount() +
					"," + results.getSubunits().getCalphaCount() +
					"," + time +
					"," + signature95 +
					"," + stoich95 +
					"," + signature30 +
					"," + stoich30 +
					"," + spaceGroup
					);
		}
	}

	private boolean isLowSymmetry(QuatSymmetryResults results) {
		return getMinFold(results.getSubunits()) > 1 && results.getRotationGroup() != null && results.getRotationGroup().getPointGroup().equals("C1");
	}

	private int getMinFold(Subunits subunits) {
		if (subunits.getFolds().size() > 1) {
			return subunits.getFolds().get(1);
		}
		return subunits.getFolds().get(0);
	}

	private void initializeCache() {
		cache = new AtomCache();
		FileParsingParameters params = cache.getFileParsingParams();
		params.setStoreEmptySeqRes(true);
		params.setAlignSeqRes(true);
		params.setParseCAOnly(true);
		params.setLoadChemCompInfo(true);
//		ChemCompGroupFactory.setChemCompProvider(new AllChemCompProvider());
		ChemCompGroupFactory.setChemCompProvider(new DownloadChemCompProvider());
	}
	
}
