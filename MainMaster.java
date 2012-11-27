import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import lejos.nxt.Button;
import lejos.nxt.LCD;
import lejos.nxt.LightSensor;
import lejos.nxt.Motor;
import lejos.nxt.comm.Bluetooth;
import lejos.nxt.comm.NXTConnection;
import lejos.nxt.comm.RConsole;
import bluetooth.BluetoothConnection;
import bluetooth.PlayerRole;
import bluetooth.StartCorner;
import bluetooth.Transmission;


public class MainMaster {
	//Variables used in establishing connection to the bluetooth server.
	private static BluetoothConnection conn;
	private static Transmission t;
	private static StartCorner corner;
	public static PlayerRole role;
	public static int dx=2,dy=2;
	public static int ax=1, ay=1;
	public static double dxCoordinate = (dx*30.48)-(30.48/2);
	public static double dyCoordinate = (dy*30.48)-(30.48/2);
	
	//Variables used for communication with slave.
	private static NXTConnection connectionToSlave;
	private static DataInputStream dis;
	private static DataOutputStream dos;
	public static final int CLOSE_CLAW = 0;
	public static final int OPEN_CLAW = 1;
	public static final int LOWER_CLAW_TO_FLOOR = 2;
	public static final int MOVE_CLAW_UP = 3;
	
	//Variables used for by the attacker/defender.
	private static USSensor usSensor = SensorAndMotorInfo.US_SENSOR;;
	private static TwoWheeledRobot patBot = new TwoWheeledRobot(Motor.A, Motor.B);
	private static Odometer odo = new Odometer(patBot, true);
	private static LCDInfo lcd;
	private static Navigation nav = Navigation.getNavigation(odo);
	private static FieldScanner fieldScanner = FieldScanner.getFieldScanner(odo);
	private static SearchAlgorithm 	searchAlgorithm = SearchAlgorithm.getSearchAlgorithm();;
	private static USLocalizer usl = new USLocalizer(odo, usSensor, USLocalizer.LocalizationType.FALLING_EDGE);
	private static LightLocalizer ll = new LightLocalizer(odo, SensorAndMotorInfo.LS_LOCALIZATION_SENSOR);
	private static LightFilter lf = new LightFilter();
	private static USFilter usf = new USFilter();
	
	public static void main(String[] args){
		//connectToBTServer();
		connectToSlave();
		RConsole.openBluetooth(10000);
		LCD.clear();
		
		int buttonChoice;
		do{
			LCD.drawString("Left - Defend" , 0, 0);
			LCD.drawString("Right - Attack",0,1);
			buttonChoice = Button.waitForAnyPress();
		}while(buttonChoice!=Button.ID_RIGHT&&buttonChoice!=Button.ID_LEFT);

		if(buttonChoice == Button.ID_LEFT){
			//Defender code
			lcd = new LCDInfo(odo);
			lf.start();
			usf.start();
			//searchAlgorithm.setDefenderLocation(dx, dy);
			
			//Perform localization prior to going to the beacon.
			usl.doLocalization();
			try{Thread.sleep(2000);}catch(InterruptedException e){}
			ll.doLocalization();
			nav.travelTo(0,0);
			setPositionBasedOnCorner();
			//nav.travelToInXandY(15.24, 90);
			//Go to the beacon and stop at the optimal position.
			goToBeacon();
			pickupBeacon();
			nav.carryingBeacon = true;
			hideBeacon();
			dropBeacon();
			nav.carryingBeacon = false;
		}else if (buttonChoice == Button.ID_RIGHT){
			//Attacker code
			lcd = new LCDInfo(odo);
			lf.start();
			usf.start();
			
			//Perform localization prior to going to the beacon.
			usl.doLocalization();
			try{Thread.sleep(2000);}catch(InterruptedException e){}
			ll.doLocalization();
			nav.travelTo(0,0);
			setPositionBasedOnCorner();
			
			//Pickup the beacon and drop it at the optimal location.
			findAndGoToBeacon();
			pickupBeacon();
			nav.travelToInXandY(ax, ay);
			dropBeacon();
		}		
		
		while (Button.waitForAnyPress() != Button.ID_ESCAPE);
		RConsole.close();
		System.exit(0);
	}
	
	public static void setPositionBasedOnCorner(){
		if(corner == StartCorner.BOTTOM_LEFT){
			odo.setPosition(new double [] {0.0, 0.0, 0.0}, new boolean [] {true, true, true});
		}else if(corner == StartCorner.BOTTOM_RIGHT){
			odo.setPosition(new double [] {10.0, 0.0, 0.0}, new boolean [] {true, true, true});
		}else if (corner == StartCorner.TOP_LEFT){
			odo.setPosition(new double [] {0.0, 10.0, 0.0}, new boolean [] {true, true, true});
		}else if(corner == StartCorner.TOP_RIGHT){
			odo.setPosition(new double [] {0.0, 10.0, 10.10}, new boolean [] {true, true, true});
		}
	}
	
	public static void connectToBTServer(){
		conn = new BluetoothConnection();
		t = conn.getTransmission();
		
		if (t == null) {
			LCD.drawString("Failed to read transmission", 0, 5);
		} else {
			//Roles and starting corner.
			corner = t.startingCorner;
			role = t.role;
			
			//Defender will pick the flag from here.
			dx = t.fx;
			dy = t.fy;
			
			// attacker will drop the flag off here.
			ax = t.dx;	
			ay = t.dy;	
		}
	}
	
	public static void connectToSlave(){
		connectionToSlave = Bluetooth.connect("ShootingBrick",NXTConnection.PACKET);
		try {
			dis = connectionToSlave.openDataInputStream();
			dos = connectionToSlave.openDataOutputStream();
		} catch (Exception e) {
			connectToSlave();
		}
	}
	
	public static void goToBeacon(){
		boolean beaconFound = false;
		int maxLight;
		double[] searchLoc;
		
		while(!beaconFound){
			searchLoc = searchAlgorithm.getNextDefenderSearchLocation();
			if(searchLoc == null){
				beaconFound = true;
				break;
			}
			nav.travelToInXandY(searchLoc[0], searchLoc[1]);
			fieldScanner.locateBeacon();
			maxLight = fieldScanner.getMaxLightReading();
			if(maxLight > Constants.LV_AT_30){
				goInBestPosition();
				beaconFound = true;
				break;
			}
		}
	}
	
	public static void hideBeacon(){
		RConsole.println("Found beacon and travelling to 0,0");
		nav.travelToInXandY(0,0);
	}
	
	public static void findAndGoToBeacon(){
		// Variables used for attacking/defending.
		boolean beaconFound = false;
		double[] nextSearchLocation;

		while (!beaconFound) {
			fieldScanner.locateBeacon();

			if (!fieldScanner.beaconLocated()) {
				// The beacon has not yet been located. Thus, we go to the next
				// position
				// in our search algorithm.
				nextSearchLocation = searchAlgorithm.getNextAttackerSearchLocation();
				if (nextSearchLocation == null) {
					beaconFound = true;
					break;
				} else {
					nav.travelToInXandY(nextSearchLocation[0],
							nextSearchLocation[1]);
				}
			} else {
				fieldScanner.turnToBeacon();
				nav.navigateTowardsLightSource(30);
				goInBestPosition();
				beaconFound = true;
				break;
				
			}
		}
	}
	
	public static void goInBestPosition(){
		fieldScanner.turnToBeacon();
		nav.navigateTowardsLightSource(15);
		nav.turnTo(odo.getTheta() - 180);
		nav.turnTo(odo.getTheta() - 9);
		nav.goStraight(18);
	}
	
	public static void pickupBeacon() {
		try {
			dos.writeInt(LOWER_CLAW_TO_FLOOR);
			dos.flush();
			while (dis.available() <= 0) {
				Thread.sleep(10);
			}
			dis.readBoolean();
			Thread.sleep(2000);

			dos.writeInt(CLOSE_CLAW);
			dos.flush();
			while (dis.available() <= 0) {
				Thread.sleep(10);
			}
			dis.readBoolean();
			Thread.sleep(2000);

			dos.writeInt(MOVE_CLAW_UP);
			dos.flush();
			while (dis.available() <= 0) {
				Thread.sleep(10);
			}
			dis.readBoolean();
			Thread.sleep(2000);
		} catch (InterruptedException e) {
		} catch (IOException e) {
		} catch (Exception e) {
		} finally {
		}
	}
	
	public static void dropBeacon(){
		try {
			dos.writeInt(LOWER_CLAW_TO_FLOOR);
			dos.flush();
			while (dis.available() <= 0) {
				Thread.sleep(10);
			}
			dis.readBoolean();
			Thread.sleep(2000);

			dos.writeInt(OPEN_CLAW);
			dos.flush();
			while (dis.available() <= 0) {
				Thread.sleep(10);
			}
			dis.readBoolean();
			Thread.sleep(2000);
			
			dos.writeInt(MOVE_CLAW_UP);
			dos.flush();
			while (dis.available() <= 0) {
				Thread.sleep(10);
			}
			dis.readBoolean();
			Thread.sleep(2000);
		} catch (InterruptedException e) {
		} catch (IOException e) {
		} catch (Exception e) {
		} finally {
		}
	}
}
