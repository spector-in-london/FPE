package ch.bfh.fpe.intEnc;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import ch.bfh.fpe.messageSpace.IntegerMessageSpace;
import ch.bfh.fpe.messageSpace.OutsideMessageSpaceException;

/**
 * 
 * @author Matthias
 *
 */
public class EME2IntegerCipher extends IntegerCipher {
	
	private static final int MIN_BIT_LENGTH = 128;	

	public EME2IntegerCipher(IntegerMessageSpace messageSpace) {
		super(messageSpace);
		if (messageSpace.getOrder().bitLength() < MIN_BIT_LENGTH) throw new IllegalArgumentException("Message space must be bigger than 128 bit");
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public BigInteger encrypt(BigInteger plaintext, byte[] key, byte[] tweak) {
		return cipher(plaintext, key, tweak, true);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public BigInteger decrypt(BigInteger ciphertext, byte[] key, byte[] tweak) {
		return cipher(ciphertext, key, tweak, false);
	}
	
	
	/**
	 * First method called from encrypt/decrypt methods. Checks input values for invalidities and throws an Exception if an argument is not valid.<br>
	 * Encryption/Decryption takes place in a do-while-loop to be sure that the output is a value inside the given message space.<br> 
	 * If not, the encrypted/decrypted value is encrypted/decrypted once again and so on. This procedure is called "Cycle Walking".
	 * @param plaintext plaintext of length 16 bytes or more
	 * @param key 48 or 64 byte EME2-AES key
	 * @param tweak value of the associated data, of arbitrary byte length (zero or more bytes)
	 * @param encryption true if this method is called for an encryption, false if for a decryption
	 * @return
	 */
	private BigInteger cipher(BigInteger input, byte[] key, byte[] tweak, boolean encryption){
		
		BigInteger maxMsValue = getMessageSpace().getOrder().subtract(BigInteger.ONE); //-1 because the order is 1 more than the max allowed value	
		if (input==null) throw new IllegalArgumentException("Input value must not be null");
		if (input.compareTo(BigInteger.ZERO)==-1) throw new IllegalArgumentException("Input value must not be negative");
		if (input.compareTo(maxMsValue)==1) throw new OutsideMessageSpaceException(input.toString());
		if (key==null || key.length != 48 && key.length != 64) throw new IllegalArgumentException("Key must be 48 or 64 bytes long");
		if (tweak==null) throw new IllegalArgumentException("Tweak must not be a null object");

		
		try {
			do{
				input = cipherFunction(input,key, tweak, encryption);
			} while (input.compareTo(maxMsValue)==1) ; //Cycle Walking: While new value is outside of message space, encipher again
		} catch (GeneralSecurityException e) {
			throw new IllegalArgumentException("A security exception occured: " + e.getMessage());
		}
		return input;
	}
	
	
	/**

	 * @param input plaintext or ciphertext of length 16 bytes or more
	 * @param key 48 or 64 byte EME2-AES key
	 * @param tweak value of the associated data, of arbitrary byte length (zero or more bytes)
	 * @param encryption true if this method is called for an encryption, false if for a decryption
	 * @return a ciphertext or a plaintext, depending on encryption or decryption
	 * @throws GeneralSecurityException wrong security parameter in AES. Should not happen because we control/check all parameters.
	 */
	private BigInteger cipherFunction(BigInteger input, byte[] key, byte[] tweak, boolean encryption) throws GeneralSecurityException {
		
		// Split input key into three subkeys
		int shift=0;
		if(key.length==64) shift=16; 
		
		byte[] key1=new byte[16+shift],  key2=new byte[16], key3=new byte[16];
		System.arraycopy(key, 0, key1, 0, 16+shift); //key1: 16 or 32 bytes for the actual AES encryption
		System.arraycopy(key, 16+shift, key2, 0, 16); //key2: 16 bytes for xor of the plaintext
		System.arraycopy(key, 32+shift, key3, 0,16); //key3: 16 bytes for xor of the tweak
		
		
		// Initialize AES with ECB-mode. For the tweak-part only the encrypt mode is used, independent of enc/dec of input 
		Cipher aesCipher = Cipher.getInstance("AES/ECB/NoPadding");
		aesCipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key1, "AES"));
			
		
		
		/* Process the arbitrary long input tweak to get a 16-byte block tweak */
		byte[] tweakInBlockSize = new byte[16];
		
		if(tweak.length==0) tweakInBlockSize = aesCipher.doFinal(key3); //If tweak is zero, encrypted key3 is taken as tweak
		else{
			ArrayList<byte[]> tweakArray = new ArrayList<byte[]>();
			ArrayList<byte[]> encTweakArray = new ArrayList<byte[]>();
			
			//Copy each 16 byte blocks of input tweak as element in tweakArray
			for (int m=0; m < tweak.length-15;m+=16){
				tweakArray.add(Arrays.copyOfRange(tweak, m, m+16)); 
			}
			//If the last block is not 16 bytes, copy the rest in tweakArray and pad it to 16 bytes
			if(tweak.length%16 != 0){
				tweakArray.add(Arrays.copyOfRange(tweak, tweak.length-(16-((-tweak.length%16)+16)%16), tweak.length)); 
				tweakArray.set(tweakArray.size()-1, padToBlocksize(tweakArray.get(tweakArray.size()-1)));
			}
			key3 = multByAlpha(key3);
			
			// xor each tweak block with key3, encrypt it and xor again with key3
			for(int i=0; i<tweakArray.size();i++){
				encTweakArray.add(xor(aesCipher.doFinal(xor(tweakArray.get(i),key3)),key3));
				key3 = multByAlpha(key3);
			}

			// xor each encrypted tweak block with the next one
			for(byte[] encTweakBlock : encTweakArray) tweakInBlockSize = xor(tweakInBlockSize,encTweakBlock);
		}
				
		
		
		/* First encryption/decryption pass	*/
		
		if (encryption==false) aesCipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key1, "AES")); //if decryption switch AES to decrypt mode
				
		byte[] inputArray = input.toByteArray();
		
		// If first byte of the input is zero, it was used by BigInteger to represent a positive value and has to be removed
		if (inputArray[0] == 0) inputArray = Arrays.copyOfRange(inputArray, 1, inputArray.length);
		
		// Copy input in a new array with the length of message space
		byte[] plaintext = new byte[getMessageSpace().getOrder().toByteArray().length]; 
		System.arraycopy(inputArray, 0, plaintext, plaintext.length-inputArray.length, inputArray.length);
		
		boolean lastPlainBlockIncomplete = false;	
		if(plaintext.length%16 != 0) lastPlainBlockIncomplete=true;	
			
		// Copy each 16 byte blocks of input plaintext as element in plainArray
		ArrayList<byte[]> plainArray = new ArrayList<byte[]>();
		for (int m=0; m < plaintext.length-15;m+=16){
			plainArray.add(Arrays.copyOfRange(plaintext, m, m+16));
		}
		// If the last block is not 16 bytes, copy the rest in plainArray
		if(lastPlainBlockIncomplete){
			plainArray.add(Arrays.copyOfRange(plaintext, (plaintext.length-(16-((-plaintext.length%16)+16)%16)), plaintext.length));
		}
				
		int indexOfLastBlock = plainArray.size()-1;
		byte[] copyOfKey2 = key2; // Save a copy of key2 before it changes
		
		
		// xor each plaintext block (except the last one) with key2 and encrypt it
		ArrayList<byte[]> encPlainArray = new ArrayList<byte[]>();
		for(int i=0; i<indexOfLastBlock;i++){
			encPlainArray.add(aesCipher.doFinal(xor(key2,plainArray.get(i))));
			key2 = multByAlpha(key2);
		}
		
		// if the last block has not 16 bytes, pad it to blocksize
		if(lastPlainBlockIncomplete) encPlainArray.add(padToBlocksize(plainArray.get(indexOfLastBlock)));
		// else encrypt it like the other ones before
		else encPlainArray.add(aesCipher.doFinal(xor(key2,plainArray.get(indexOfLastBlock))));

		
	
		/* Intermediate mixing part */
		//the denotations mp,m,m1,mc,mc1,mm of the masks are adopted from the definition of EME2
		byte[] mp, m, m1, mc, mc1, mm = null, Cm = null;
		
		// xor each encrypted plaintext block with the next one and the tweak and store it in mp
		mp = tweakInBlockSize;
		for (byte[] encPlainBlock : encPlainArray) mp = xor(mp,encPlainBlock);
		
		
		if(lastPlainBlockIncomplete){
			mm = aesCipher.doFinal(mp);
			mc = mc1 = aesCipher.doFinal(mm);	
		} else {
			mc = mc1 = aesCipher.doFinal(mp);	
		}
		m = m1 = xor(mp,mc);

		ArrayList<byte[]> cipherArray = new ArrayList<byte[]>();
		cipherArray.add(new byte[16]); //placeholder for first element, is replaced later
		
		for (int i=1; i<indexOfLastBlock;i++){
			if ((i-1)%128 > 0) { 
				m = multByAlpha(m);
				cipherArray.add(xor(encPlainArray.get(i),m));
			}else{ //Recalculate mask m after every 2048 bytes
				mp = xor(encPlainArray.get(i),m1);
				mc = aesCipher.doFinal(mp);
				m = xor(mp,mc);
				cipherArray.add(xor(mc,m1));	
				}
			}
		
		if(lastPlainBlockIncomplete){
			Cm = xor(plainArray.get(indexOfLastBlock),mm);
			cipherArray.add(padToBlocksize(Cm));	
		} else if((indexOfLastBlock-1)%128 > 0) {
			m = multByAlpha(m);
			cipherArray.add(xor(encPlainArray.get(indexOfLastBlock),m));
		} else {
			cipherArray.add(xor(aesCipher.doFinal(xor(m1,encPlainArray.get(indexOfLastBlock))),m1));
		}
		
		// xor each encrypted block with the next one and set it as first element of the ciphertext array
		byte[] firstElementTemp = xor(mc1,tweakInBlockSize);
		for (byte[] cipherBlock : cipherArray){
			firstElementTemp = xor(firstElementTemp,cipherBlock);
		}
		cipherArray.set(0,firstElementTemp);
		
		
		
		/* Second encryption/decryption pass */
		
		key2 = copyOfKey2; // Restore key2 with the original value
		ArrayList<byte[]> encCipherArray = new ArrayList<byte[]>();
		
		for(int i=0; i<indexOfLastBlock; i++){
			encCipherArray.add(xor(aesCipher.doFinal(cipherArray.get(i)),key2));
			key2 = multByAlpha(key2);
		}
		
		/* Note that we computed the last ciphertext block above if it was short */
		if(lastPlainBlockIncomplete) encCipherArray.add(Cm);
		else encCipherArray.add(xor(aesCipher.doFinal(cipherArray.get(indexOfLastBlock)),key2));
		
		
		byte[] output = new byte[plaintext.length];
		int i = 0;
		for (byte[] encCipherBlock : encCipherArray){
			for (byte byteValue : encCipherBlock){
				output[i] = byteValue;
				i++;
			}
		}
		
		return new BigInteger(1,output);
	}

	
	/**
	 * 
	 * @param input
	 * @return
	 */
	private static byte[] padToBlocksize(byte[] input){
		if(input.length==16) return input;
		byte[] output = new byte[input.length + (((-input.length%16)+16)%16)];
		System.arraycopy(input, 0, output, 0, input.length);
		output[input.length] = (byte) 128; //Set the first bit in the first padded block
		return output;
	}
	
	
	
	/**
	 * Multiplies a 16-byte input value by a primitive element α in the field GF(2^128) ("Galois Field Multiplication")
	 * @param input ByteArray to be multiplied
	 * @return Multiplied ByteArray
	 */
	private static byte[] multByAlpha(byte[] input){
		byte[] output = new byte[16];
		
		for(int i=0;i<16;i++){
			output[i] = (byte) ((2 * input[i]) % 256);
			if(i>0 && input[i-1] > 127) output[i] = (byte) (output[i] + 1);
		}
		if (input[15] > 127) output[0] = (byte) (output[0] ^ 0x87);
		return output;	
	}
	
	/**
	 * Calculates the XOR value for two given ByteArrays.
	 * @param array1 First ByteArray
	 * @param array2 Second ByteArray
	 * @return a ByteArray with the XOR value
	 */
	private static byte[] xor(byte[] array1, byte[] array2)
	{
		byte[] xorArray = new byte[array1.length];
		int i = 0;
		for (byte b : array1){
			xorArray[i] = (byte) (b ^ array2[i++]);
		}
		return xorArray;
	}
	
	
}