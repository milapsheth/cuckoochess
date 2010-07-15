/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.petero.droidfish.gamelogic;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Scanner;

import org.petero.droidfish.GUIInterface;
import org.petero.droidfish.GameMode;
import org.petero.droidfish.engine.ComputerPlayer;
import org.petero.droidfish.gamelogic.Game.GameState;

/**
 * The glue between the chess engine and the GUI.
 * @author petero
 */
public class ChessController {
    private ComputerPlayer computerPlayer = null;
    private Game game;
    private GUIInterface gui;
    private GameMode gameMode;
    private Thread computerThread;
    private Thread analysisThread;

    // Search statistics
    private String thinkingPV;

    class SearchListener implements org.petero.droidfish.gamelogic.SearchListener {
        private int currDepth = 0;
        private int currMoveNr = 0;
        private String currMove = "";
        private int currNodes = 0;
        private int currNps = 0;
        private int currTime = 0;

        private int pvDepth = 0;
        private int pvScore = 0;
        private int pvTime = 0;
        private int pvNodes = 0;
        private boolean pvIsMate = false;
        private boolean pvUpperBound = false;
        private boolean pvLowerBound = false;
        private String bookInfo = "";
        private String pvStr = "";

        private final void setSearchInfo() {
            StringBuilder buf = new StringBuilder();
            buf.append(String.format("%n[%d] ", pvDepth));
            if (pvUpperBound) {
                buf.append("<=");
            } else if (pvLowerBound) {
                buf.append(">=");
            }
            if (pvIsMate) {
                buf.append(String.format("m%d", pvScore));
            } else {
                buf.append(String.format("%.2f", pvScore / 100.0));
            }
            buf.append(pvStr);
            buf.append("\n");
            buf.append(String.format("d:%d %d:%s t:%.2f n:%d nps:%d", currDepth,
                    currMoveNr, currMove, currTime / 1000.0, currNodes, currNps));
            if (bookInfo.length() > 0) {
            	buf.append("\n");
            	buf.append(bookInfo);
            }
            final String newPV = buf.toString();
            gui.runOnUIThread(new Runnable() {
                public void run() {
                    thinkingPV = newPV;
                    setThinkingPV();
                }
            });
        }

        public void notifyDepth(int depth) {
            currDepth = depth;
            setSearchInfo();
        }

        public void notifyCurrMove(Move m, int moveNr) {
            currMove = TextIO.moveToString(new Position(game.pos), m, false);
            currMoveNr = moveNr;
            setSearchInfo();
        }

        public void notifyPV(int depth, int score, int time, int nodes, int nps, boolean isMate,
                boolean upperBound, boolean lowerBound, ArrayList<Move> pv) {
            pvDepth = depth;
            pvScore = score;
            pvTime = currTime = time;
            pvNodes = currNodes = nodes;
            currNps = nps;
            pvIsMate = isMate;
            pvUpperBound = upperBound;
            pvLowerBound = lowerBound;

            StringBuilder buf = new StringBuilder();
            Position pos = new Position(game.pos);
            UndoInfo ui = new UndoInfo();
            for (Move m : pv) {
                buf.append(String.format(" %s", TextIO.moveToString(pos, m, false)));
                pos.makeMove(m, ui);
            }
            pvStr = buf.toString();
            setSearchInfo();
        }

        public void notifyStats(int nodes, int nps, int time) {
            currNodes = nodes;
            currNps = nps;
            currTime = time;
            setSearchInfo();
        }

		@Override
		public void notifyBookInfo(String bookInfo) {
			this.bookInfo = bookInfo;
			setSearchInfo();
		}
    }
    SearchListener listener;
    
    public ChessController(GUIInterface gui) {
        this.gui = gui;
        listener = new SearchListener();
        thinkingPV = "";
    }

    private final static class SearchStatus {
    	boolean searchResultWanted = true;
    }
    SearchStatus ss = new SearchStatus();
    
    public final void newGame(GameMode gameMode) {
        ss.searchResultWanted = false;
        stopComputerThinking();
        stopAnalysis();
        this.gameMode = gameMode;
        if (computerPlayer == null) {
        	computerPlayer = new ComputerPlayer();
        	computerPlayer.setListener(listener);
        }
       	game = new Game(computerPlayer);
    }

    public final void startGame() {
        setSelection(); 
        updateGUI();
        updateComputeThreads(true);
    }
    
    private final void updateComputeThreads(boolean clearPV) {
    	boolean analysis = gameMode.analysisMode();
    	boolean computersTurn = !gameMode.humansTurn(game.pos.whiteMove);
    	if (!analysis)
    		stopAnalysis();
    	if (!computersTurn)
    		stopComputerThinking();
    	if (clearPV)
			thinkingPV = "";
        if (analysis)
        	startAnalysis();
        if (computersTurn)
            startComputerThinking();
    }

    /** Set game mode. */
	public final void setGameMode(GameMode newMode) {
		if (!gameMode.equals(newMode)) {
			if (newMode.humansTurn(game.pos.whiteMove))
				ss.searchResultWanted = false;
			gameMode = newMode;
			updateComputeThreads(true);
			updateGUI();
		}
	}

	public final void setPosHistory(List<String> posHistStr) {
		try {
			String fen = posHistStr.get(0);
			Position pos = TextIO.readFEN(fen);
			game.processString("new");
			game.pos = pos;
			String[] strArr = posHistStr.get(1).split(" ");
			final int arrLen = strArr.length;
			for (int i = 0; i < arrLen; i++) {
				game.processString(strArr[i]);
			}
			int numUndo = Integer.parseInt(posHistStr.get(2));
			for (int i = 0; i < numUndo; i++) {
				game.processString("undo");
			}
		} catch (ChessParseError e) {
			// Just ignore invalid positions
		}
    }
    
    public final List<String> getPosHistory() {
    	return game.getPosHistory();
    }
    
    public final String getFEN() {
    	return TextIO.toFEN(game.pos);
    }
    
    /** Convert current game to PGN format. */
    public final String getPGN() {
    	StringBuilder pgn = new StringBuilder();
    	List<String> posHist = getPosHistory();
    	String fen = posHist.get(0);
        String moves = game.getMoveListString(true);
        if (game.getGameState() == GameState.ALIVE)
        	moves += " *";
    	int year, month, day;
    	{
    		Calendar now = GregorianCalendar.getInstance();
    		year = now.get(Calendar.YEAR);
    		month = now.get(Calendar.MONTH) + 1;
    		day = now.get(Calendar.DAY_OF_MONTH);
    	}
    	pgn.append(String.format("[Date \"%04d.%02d.%02d\"]%n", year, month, day));
    	String engine = ComputerPlayer.engineName;
    	String white = gameMode.playerWhite() ? "Player" : engine;
    	String black = gameMode.playerBlack() ? "Player" : engine;
    	pgn.append(String.format("[White \"%s\"]%n", white));
    	pgn.append(String.format("[Black \"%s\"]%n", black));
    	pgn.append(String.format("[Result \"%s\"]%n", game.getPGNResultString()));
    	if (!fen.equals(TextIO.startPosFEN)) {
    		pgn.append(String.format("[FEN \"%s\"]%n", fen));
    		pgn.append("[SetUp \"1\"]\n");
    	}
    	pgn.append("\n");
		String[] strArr = moves.split(" ");
    	int currLineLength = 0;
		final int arrLen = strArr.length;
		for (int i = 0; i < arrLen; i++) {
			String move = strArr[i].trim();
			int moveLen = move.length();
			if (moveLen > 0) {
				if (currLineLength + 1 + moveLen >= 80) {
					pgn.append("\n");
					pgn.append(move);
					currLineLength = moveLen;
				} else {
					if (currLineLength > 0) {
						pgn.append(" ");
						currLineLength++;
					}
					pgn.append(move);
					currLineLength += moveLen;
				}
			}
		}
    	pgn.append("\n\n");
    	return pgn.toString();
    }

    public final void setFENOrPGN(String fenPgn) throws ChessParseError {
       	Game newGame = new Game(computerPlayer);
    	try {
    		Position pos = TextIO.readFEN(fenPgn);
    		newGame.pos = pos;
    	} catch (ChessParseError e) {
    		// Try read as PGN instead
    		setPGN(newGame, fenPgn);
    	}
    	ss.searchResultWanted = false;
    	game = newGame;
    	stopAnalysis();
    	stopComputerThinking();
		updateComputeThreads(true);
		gui.setSelection(-1);
		updateGUI();
    }

    public final void setPGN(Game newGame, String pgn) throws ChessParseError {
    	// First pass, remove comments
    	{
    		StringBuilder out = new StringBuilder();
    		Scanner sc = new Scanner(pgn);
    		sc.useDelimiter("");
    		while (sc.hasNext()) {
    			String c = sc.next();
    			if (c.equals("{")) {
    				sc.skip("[^}]*}");
    			} else if (c.equals(";")) {
    				sc.skip("[^\n]*\n");
    			} else {
    				out.append(c);
    			}
    		}
    		pgn = out.toString();
    	}

    	// Parse tag section
    	Position pos = TextIO.readFEN(TextIO.startPosFEN);
    	Scanner sc = new Scanner(pgn);
    	sc.useDelimiter("\\s+");
    	while (sc.hasNext("\\[.*")) {
    		String tagName = sc.next();
    		if (tagName.length() > 1) {
    			tagName = tagName.substring(1);
    		} else {
    			tagName = sc.next();
    		}
    		String tagValue = sc.findWithinHorizon(".*\\]", 0);
    		tagValue = tagValue.trim();
    		if (tagValue.charAt(0) == '"')
    			tagValue = tagValue.substring(1);
    		if (tagValue.charAt(tagValue.length()-1) == ']')
    			tagValue = tagValue.substring(0, tagValue.length() - 1);
    		if (tagValue.charAt(tagValue.length()-1) == '"')
    			tagValue = tagValue.substring(0, tagValue.length() - 1);
    		if (tagName.equals("FEN")) {
    			pos = TextIO.readFEN(tagValue);
    		}
    	}
    	newGame.pos = pos;

    	// Handle (ignore) recursive annotation variations
    	{
    		StringBuilder out = new StringBuilder();
    		sc.useDelimiter("");
    		int level = 0;
    		while (sc.hasNext()) {
    			String c = sc.next();
    			if (c.equals("(")) {
    				level++;
    			} else if (c.equals(")")) {
    				level--;
    			} else if (level == 0) {
    				out.append(c);
    			}
    		}
    		pgn = out.toString();
    	}

    	// Parse move text section
    	sc = new Scanner(pgn);
    	sc.useDelimiter("\\s+");
    	while (sc.hasNext()) {
    		String strMove = sc.next();
    		strMove = strMove.replaceFirst("\\$?[0-9]*\\.*([^?!]*)[?!]*", "$1");
    		if (strMove.length() == 0) continue;
    		Move m = TextIO.stringToMove(newGame.pos, strMove);
    		if (m == null)
    			break;
    		newGame.processString(strMove);
    	}
    }

    /** True if human's turn to make a move. (True in analysis mode.) */
    public final boolean humansTurn() {
    	return gameMode.humansTurn(game.pos.whiteMove);
    }

    /** True if the computer is thinking about next move. (False in analysis mode.) */
    public final boolean computerThinking() {
    	return computerThread != null;
    }

    public final void undoMove() {
    	if (game.getLastMove() != null) {
    		ss.searchResultWanted = false;
    		game.processString("undo");
    		if (!humansTurn()) {
    			if (game.getLastMove() != null) {
    				game.processString("undo");
    				if (!humansTurn()) {
    					game.processString("redo");
    				}
    			} else {
    				// Don't undo first white move if playing black vs computer,
    				// because that would cause computer to immediately make
    				// a new move and the whole redo history will be lost.
    				game.processString("redo");
    			}
    		}
    		stopAnalysis();
			stopComputerThinking();
    		updateComputeThreads(true);
    		setSelection();
    		updateGUI();
    	}
    }

    public final void redoMove() {
    	if (game.canRedoMove()) {
    		ss.searchResultWanted = false;
    		game.processString("redo");
    		if (!humansTurn() && game.canRedoMove()) {
    			game.processString("redo");
    			if (!humansTurn())
    				game.processString("undo");
    		}
    		stopAnalysis();
			stopComputerThinking();
    		updateComputeThreads(true);
    		setSelection();
    		updateGUI();
    	}
    }

    public final void makeHumanMove(Move m) {
        if (humansTurn()) {
            if (doMove(m)) {
            	ss.searchResultWanted = false;
                stopAnalysis();
    			stopComputerThinking();
                updateComputeThreads(true);
                updateGUI();
            } else {
                gui.setSelection(-1);
            }
        }
    }

    Move promoteMove;
    public final void reportPromotePiece(int choice) {
    	final boolean white = game.pos.whiteMove;
    	int promoteTo;
        switch (choice) {
            case 1:
                promoteTo = white ? Piece.WROOK : Piece.BROOK;
                break;
            case 2:
                promoteTo = white ? Piece.WBISHOP : Piece.BBISHOP;
                break;
            case 3:
                promoteTo = white ? Piece.WKNIGHT : Piece.BKNIGHT;
                break;
            default:
                promoteTo = white ? Piece.WQUEEN : Piece.BQUEEN;
                break;
        }
        promoteMove.promoteTo = promoteTo;
        Move m = promoteMove;
        promoteMove = null;
        makeHumanMove(m);
    }

    /**
     * Move a piece from one square to another.
     * @return True if the move was legal, false otherwise.
     */
    final private boolean doMove(Move move) {
        Position pos = game.pos;
        ArrayList<Move> moves = new MoveGen().pseudoLegalMoves(pos);
        moves = MoveGen.removeIllegal(pos, moves);
        int promoteTo = move.promoteTo;
        for (Move m : moves) {
            if ((m.from == move.from) && (m.to == move.to)) {
                if ((m.promoteTo != Piece.EMPTY) && (promoteTo == Piece.EMPTY)) {
                	promoteMove = m;
                	gui.requestPromotePiece();
                	return false;
                }
                if (m.promoteTo == promoteTo) {
                    String strMove = TextIO.moveToString(pos, m, false);
                    game.processString(strMove);
                    return true;
                }
            }
        }
    	gui.reportInvalidMove(move);
        return false;
    }

    final private void updateGUI() {
        String str = game.pos.whiteMove ? "White's move" : "Black's move";
        if (computerThread != null) str += " (thinking)";
        if (analysisThread != null) str += " (analyzing)";
        if (game.getGameState() != Game.GameState.ALIVE) {
            str = game.getGameStateString();
        }
        gui.setStatusString(str);
        gui.setMoveListString(game.getMoveListString(true));
        setThinkingPV();
        gui.setPosition(game.pos);
    }

    private final void setThinkingPV() {
    	String str = "";
    	if (gui.showThinking()) {
            str = thinkingPV;
        }
        gui.setThinkingString(str);
    }

    final private void setSelection() {
        Move m = game.getLastMove();
        int sq = (m != null) ? m.to : -1;
        gui.setSelection(sq);
    }

    private final synchronized void startComputerThinking() {
    	if (analysisThread != null) return;
    	if (game.getGameState() != GameState.ALIVE) return;
    	if (computerThread == null) {
    		ss = new SearchStatus();
			final TwoReturnValues<Position, ArrayList<Move>> ph = game.getUCIHistory();
			final Game g = game;
			final boolean haveDrawOffer = g.haveDrawOffer();
			final Position currPos = new Position(g.pos);
    		computerThread = new Thread(new Runnable() {
    			public void run() {
    				computerPlayer.timeLimit(gui.timeLimit());
    				final String cmd = computerPlayer.getCommand(ph.first, ph.second, currPos, haveDrawOffer);
    				final SearchStatus localSS = ss;
    				gui.runOnUIThread(new Runnable() {
    					public void run() {
    						if (!localSS.searchResultWanted)
    							return;
    						g.processString(cmd);
    						thinkingPV = "";
    						stopComputerThinking();
    						stopAnalysis(); // To force analysis to restart for new position
    						updateComputeThreads(true);
    						setSelection();
    						updateGUI();
    					}
    				});
    			}
    		});
    		thinkingPV = "";
    		computerThread.start();
    		updateGUI();
        }
    }

    private final synchronized void stopComputerThinking() {
        if (computerThread != null) {
            computerPlayer.timeLimit(0);
            try {
                computerThread.join();
            } catch (InterruptedException ex) {
                System.out.printf("Could not stop computer thread%n");
            }
            computerThread = null;
            updateGUI();
        }
    }

    private final synchronized void startAnalysis() {
    	if (gameMode.analysisMode()) {
    		if (computerThread != null) return;
            if (analysisThread == null) {
            	analysisThread = new Thread(new Runnable() {
            		public void run() {
            			computerPlayer.timeLimit(gui.timeLimit());
            			TwoReturnValues<Position, ArrayList<Move>> ph = game.getUCIHistory();
            			final Game g = game;
            			computerPlayer.analyze(ph.first, ph.second, new Position(g.pos),
            								   g.haveDrawOffer());
            		}
            	});
            	thinkingPV = "";
                analysisThread.start();
                updateGUI();
            }
        }
    }
    private final synchronized void stopAnalysis() {
        if (analysisThread != null) {
            computerPlayer.timeLimit(0);
            try {
                analysisThread.join();
            } catch (InterruptedException ex) {
                System.out.printf("Could not stop analysis thread%n");
            }
            analysisThread = null;
            thinkingPV = "";
            updateGUI();
        }
    }

    public final synchronized void setTimeLimit() {
        if (computerThread != null) {
            computerPlayer.timeLimit(gui.timeLimit());
        }
    }

    public final void shutdownEngine() {
    	gameMode = new GameMode(3); // Set two player mode
    	stopComputerThinking();
    	stopAnalysis();
    	computerPlayer.shutdownEngine();
    }

}
