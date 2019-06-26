{nixpkgs ? <nixpkgs>}: with import nixpkgs {};

stdenv.mkDerivation {
  name = "resume";
  src = ./.;

  buildInputs = [
    openssl
  ];

  #buildPhase = ''
    #xelatex -file-line-error -interaction=nonstopmode bianque_essay.tex
    ##bibtex bianque_essay.aux
    #xelatex -file-line-error -interaction=nonstopmode bianque_essay.tex
    #xelatex -file-line-error -interaction=nonstopmode bianque_essay.tex
  #'';

  #installPhase = ''
    #cp bianque_essay.pdf $out
  #'';

}
